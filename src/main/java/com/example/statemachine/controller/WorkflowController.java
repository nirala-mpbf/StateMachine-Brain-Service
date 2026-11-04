package com.example.statemachine.controller;

import lombok.AllArgsConstructor;
import com.example.statemachine.constants.ApiErrorConstants;
import com.example.statemachine.constants.ApiPropertyConstants;
import com.example.statemachine.constants.RequestAttributeConstants;
import com.example.statemachine.constants.WorkflowConstants;
import com.example.statemachine.dto.api.WorkflowCommandMetadata;
import com.example.statemachine.dto.api.WorkflowRequest;
import com.example.statemachine.dto.api.WorkflowResponse;
import com.example.statemachine.enums.LogValueSanitizerFunction;
import com.example.statemachine.enums.WorkflowRequestMetadataFunction;
import com.example.statemachine.service.WorkflowCommandGateway;
import com.example.statemachine.validation.WorkflowRequestValidator;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@OpenAPIDefinition(info = @Info(title = "State Machine Brain API", version = "1.0",
        description = "Database-configured workflows with JDBC persistence and idempotent mutations."))
@Tag(name = "Workflows", description = "Create workflows, inspect progress and request workflow step execution.")
@Slf4j
@RestController
@RequestMapping(WorkflowConstants.WORKFLOWS_PATH)
@AllArgsConstructor
public class WorkflowController {

    private final WorkflowRequestValidator workflowRequestValidator;
    private final WorkflowCommandGateway workflowCommandGateway;

    @Operation(
            operationId = "processWorkflowCommand",
            summary = "Create a workflow, inspect progress, or request a workflow step",
            description = """
            Coordinates workflow steps requested by the caller. Event names are commands:
            VERIFY_OTP, UPDATE_PROFILE, UPLOAD_ACCOUNT_STATEMENT and UPLOAD_UDYAM_DOCUMENT.
            External service integrations for these tasks are planned but not implemented.
            The current testing implementation applies configured transitions and bookkeeping
            actions only; an applied event does not yet prove a business task succeeded.
            When integrations are added, advance the workflow only after the external task succeeds.

            | Operation | Required metadata | Optional metadata | Forbidden metadata | HTTP |
            | --- | --- | --- | --- | --- |
            | CREATE | operation, requestId, referenceId, idempotencyKey | workflowKey (defaults to onboarding) | event | 201 |
            | GET_STATE | operation, requestId, referenceId | — | event, workflowKey, idempotencyKey | 200 |
            | SUBMIT_EVENT | operation, requestId, referenceId, event, idempotencyKey | — | workflowKey | 200 |

            Both metadata and payload are required objects; payload must be {}. Strings are
            case sensitive and nonblank. Supported operations are CREATE, GET_STATE and
            SUBMIT_EVENT. Reference ID format checks (PAN/mobile/email) are not yet implemented.
            requestId and idempotencyKey are opaque caller-supplied tokens. Unknown fields, explicit nulls, non-string metadata,
            duplicate JSON keys and trailing JSON are rejected.

            CREATE selects the highest ACTIVE definition version. Existing references keep
            their definition version. GET_STATE returns the current persisted snapshot.
            SUBMIT_EVENT requests one configured workflow step; the resulting state is persisted.

            Seeded onboarding commands, in order:
            1. VERIFY_OTP: OTP_VERIFICATION → PROFILE_DETAILS
            2. UPDATE_PROFILE: PROFILE_DETAILS → ACCOUNT_STATEMENT_UPLOAD
            3. UPLOAD_ACCOUNT_STATEMENT: ACCOUNT_STATEMENT_UPLOAD → UDYAM_DOCUMENT_UPLOAD
            4. UPLOAD_UDYAM_DOCUMENT: UDYAM_DOCUMENT_UPLOAD → COMPLETED

            Other definitions may use other events. availableTransitions lists configured
            possibilities. nextEvent/nextState appear only
            when exactly one outgoing transition exists. Terminal states have completed=true
            and no available transitions. Internal transitions can apply without changing state.

            Retry CREATE or SUBMIT_EVENT with the same globally scoped idempotencyKey and
            identical operation/referenceId/event/workflowKey. requestId may change. Successful
            retries return the original HTTP status, payload and processedAt, echo the new
            requestId, and set idempotencyReplayed=true. This snapshot may be older than current
            progress; use GET_STATE for the latest state. Omitted workflowKey and onboarding
            are equivalent on CREATE. Different inputs with the same key return 409.
            Idempotency records are stored in Redis without automatic expiration. Confirmed
            database rollbacks permit retry with the same inputs. Concurrent or uncertain
            requests return IDEMPOTENCY_IN_PROGRESS (409); Redis failures return
            IDEMPOTENCY_UNAVAILABLE (503). A database commit and Redis response storage
            are separate: an interrupted post-commit save requires recovery, not a new key.

            Errors have payload=null and error.code/description. Metadata is echoed when
            recoverable; binding failures may omit caller metadata. X-Correlation-Id traces
            the HTTP attempt independently of requestId and idempotencyKey.
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Select an operation example. Both objects are required; payload must be {}. Omit forbidden fields instead of sending null.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WorkflowRequest.class,
                                    requiredProperties = {ApiPropertyConstants.METADATA, ApiPropertyConstants.PAYLOAD},
                                    additionalProperties = Schema.AdditionalPropertiesValue.FALSE),
                            examples = {
                                @ExampleObject(name = "01 - Create onboarding",
                                        value = """
                                            {
                                              "metadata": {"operation":"CREATE","requestId":"req-101","referenceId":"APP-101","idempotencyKey":"APP-101-create","workflowKey":"onboarding"},
                                              "payload": {}
                                            }
                                            """),
                                @ExampleObject(name = "02 - Get current progress",
                                        value = """
                                            {
                                              "metadata": {"operation":"GET_STATE","requestId":"req-101","referenceId":"APP-101"},
                                              "payload": {}
                                            }
                                            """),
                                @ExampleObject(name = "03 - Verify OTP",
                                        value = """
                                            {
                                              "metadata": {"operation":"SUBMIT_EVENT","requestId":"req-101","referenceId":"APP-101","event":"VERIFY_OTP","idempotencyKey":"APP-101-VERIFY_OTP"},
                                              "payload": {}
                                            }
                                            """),
                                @ExampleObject(name = "04 - Update profile",
                                        value = """
                                            {
                                              "metadata": {"operation":"SUBMIT_EVENT","requestId":"req-101","referenceId":"APP-101","event":"UPDATE_PROFILE","idempotencyKey":"APP-101-UPDATE_PROFILE"},
                                              "payload": {}
                                            }
                                            """),
                                @ExampleObject(name = "05 - Upload account statement",
                                        value = """
                                            {
                                              "metadata": {"operation":"SUBMIT_EVENT","requestId":"req-101","referenceId":"APP-101","event":"UPLOAD_ACCOUNT_STATEMENT","idempotencyKey":"APP-101-UPLOAD_ACCOUNT_STATEMENT"},
                                              "payload": {}
                                            }
                                            """),
                                @ExampleObject(name = "06 - Upload Udyam document",
                                        value = """
                                            {
                                              "metadata": {"operation":"SUBMIT_EVENT","requestId":"req-101","referenceId":"APP-101","event":"UPLOAD_UDYAM_DOCUMENT","idempotencyKey":"APP-101-UPLOAD_UDYAM_DOCUMENT"},
                                              "payload": {}
                                            }
                                            """)
                            })))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "State read, event applied, or original SUBMIT_EVENT success replayed. Custom workflow examples are illustrative and are not seeded.",
                headers = @Header(name = WorkflowConstants.CORRELATION_ID_HEADER, description = "Server-generated ID for this HTTP attempt.",
                        schema = @Schema(type = "string", format = "uuid")),
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(implementation = WorkflowResponse.class),
                        examples = {
                            @ExampleObject(name = "Current progress",
                                    value = """
                                        {
                                          "metadata": {"operation":"GET_STATE","requestId":"req-101","referenceId":"APP-101","workflowKey":"onboarding","status":"SUCCESS","processedAt":"2026-11-04T06:00:00Z"},
                                          "payload": {"referenceId":"APP-101","workflowKey":"onboarding","definitionVersion":1,"currentState":"PROFILE_DETAILS","nextEvent":"UPDATE_PROFILE","nextState":"ACCOUNT_STATEMENT_UPLOAD","completed":false,"availableTransitions":[{"event":"UPDATE_PROFILE","nextState":"ACCOUNT_STATEMENT_UPLOAD"}]}
                                        }
                                        """),
                            @ExampleObject(name = "Completed workflow state",
                                    value = """
                                        {
                                          "metadata": {"operation":"GET_STATE","requestId":"req-101","referenceId":"APP-101","workflowKey":"onboarding","status":"SUCCESS","processedAt":"2026-11-04T06:00:00Z"},
                                          "payload": {"referenceId":"APP-101","workflowKey":"onboarding","definitionVersion":1,"currentState":"COMPLETED","completed":true,"availableTransitions":[]}
                                        }
                                        """),
                            @ExampleObject(name = "Event applied",
                                    value = """
                                        {
                                          "metadata": {"operation":"SUBMIT_EVENT","requestId":"req-101","referenceId":"APP-101","event":"VERIFY_OTP","idempotencyKey":"APP-101-VERIFY_OTP","workflowKey":"onboarding","status":"SUCCESS","processedAt":"2026-11-04T06:00:00Z","idempotencyReplayed":false},
                                          "payload": {"referenceId":"APP-101","workflowKey":"onboarding","definitionVersion":1,"currentState":"PROFILE_DETAILS","nextEvent":"UPDATE_PROFILE","nextState":"ACCOUNT_STATEMENT_UPLOAD","completed":false,"availableTransitions":[{"event":"UPDATE_PROFILE","nextState":"ACCOUNT_STATEMENT_UPLOAD"}],"eventOutcome":"APPLIED"}
                                        }
                                        """),
                            @ExampleObject(name = "Workflow completed",
                                    value = """
                                        {
                                          "metadata": {"operation":"SUBMIT_EVENT","requestId":"req-101","referenceId":"APP-101","event":"UPLOAD_UDYAM_DOCUMENT","idempotencyKey":"APP-101-UPLOAD_UDYAM_DOCUMENT","workflowKey":"onboarding","status":"SUCCESS","processedAt":"2026-11-04T06:00:00Z","idempotencyReplayed":false},
                                          "payload": {"referenceId":"APP-101","workflowKey":"onboarding","definitionVersion":1,"currentState":"COMPLETED","completed":true,"availableTransitions":[],"eventOutcome":"APPLIED"}
                                        }
                                        """),
                            @ExampleObject(name = "Idempotent event replay",
                                    value = """
                                        {
                                          "metadata": {"operation":"SUBMIT_EVENT","requestId":"req-101-retry","referenceId":"APP-101","event":"VERIFY_OTP","idempotencyKey":"APP-101-VERIFY_OTP","workflowKey":"onboarding","status":"SUCCESS","processedAt":"2026-11-04T06:00:00Z","idempotencyReplayed":true},
                                          "payload": {"referenceId":"APP-101","workflowKey":"onboarding","definitionVersion":1,"currentState":"PROFILE_DETAILS","nextEvent":"UPDATE_PROFILE","nextState":"ACCOUNT_STATEMENT_UPLOAD","completed":false,"availableTransitions":[{"event":"UPDATE_PROFILE","nextState":"ACCOUNT_STATEMENT_UPLOAD"}],"eventOutcome":"APPLIED"}
                                        }
                                        """),
                            @ExampleObject(name = "Branching workflow (custom definition)",
                                    value = """
                                        {
                                          "metadata": {"operation":"GET_STATE","requestId":"req-101","referenceId":"APP-101","workflowKey":"custom-review","status":"SUCCESS","processedAt":"2026-11-04T06:00:00Z"},
                                          "payload": {"referenceId":"APP-101","workflowKey":"custom-review","definitionVersion":1,"currentState":"UNDER_REVIEW","completed":false,"availableTransitions":[{"event":"APPROVED","nextState":"COMPLETED"},{"event":"CHANGES_REQUESTED","nextState":"NEEDS_CHANGES"}]}
                                        }
                                        """),
                            @ExampleObject(name = "Internal transition (custom definition)",
                                    value = """
                                        {
                                          "metadata": {"operation":"SUBMIT_EVENT","requestId":"req-101","referenceId":"APP-101","event":"REVIEW_NOTE_ADDED","idempotencyKey":"APP-101-REVIEW_NOTE_ADDED","workflowKey":"custom-review","status":"SUCCESS","processedAt":"2026-11-04T06:00:00Z","idempotencyReplayed":false},
                                          "payload": {"referenceId":"APP-101","workflowKey":"custom-review","definitionVersion":1,"currentState":"UNDER_REVIEW","completed":false,"availableTransitions":[{"event":"REVIEW_NOTE_ADDED","nextState":"UNDER_REVIEW"},{"event":"APPROVED","nextState":"COMPLETED"}],"eventOutcome":"APPLIED"}
                                        }
                                        """)
                        })),
        @ApiResponse(responseCode = "201", description = "New workflow created, or original CREATE success replayed.",
                headers = @Header(name = WorkflowConstants.CORRELATION_ID_HEADER, description = "Server-generated ID for this HTTP attempt.",
                        schema = @Schema(type = "string", format = "uuid")),
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(implementation = WorkflowResponse.class),
                        examples = {
                            @ExampleObject(name = "Workflow created",
                                    value = """
                                        {
                                          "metadata": {"operation":"CREATE","requestId":"req-101","referenceId":"APP-101","idempotencyKey":"APP-101-create","workflowKey":"onboarding","status":"SUCCESS","processedAt":"2026-11-04T06:00:00Z","idempotencyReplayed":false},
                                          "payload": {"referenceId":"APP-101","workflowKey":"onboarding","definitionVersion":1,"currentState":"OTP_VERIFICATION","nextEvent":"VERIFY_OTP","nextState":"PROFILE_DETAILS","completed":false,"availableTransitions":[{"event":"VERIFY_OTP","nextState":"PROFILE_DETAILS"}]}
                                        }
                                        """),
                            @ExampleObject(name = "Idempotent create replay",
                                    value = """
                                        {
                                          "metadata": {"operation":"CREATE","requestId":"req-101-retry","referenceId":"APP-101","idempotencyKey":"APP-101-create","workflowKey":"onboarding","status":"SUCCESS","processedAt":"2026-11-04T06:00:00Z","idempotencyReplayed":true},
                                          "payload": {"referenceId":"APP-101","workflowKey":"onboarding","definitionVersion":1,"currentState":"OTP_VERIFICATION","nextEvent":"VERIFY_OTP","nextState":"PROFILE_DETAILS","completed":false,"availableTransitions":[{"event":"VERIFY_OTP","nextState":"PROFILE_DETAILS"}]}
                                        }
                                        """)
                        })),
        @ApiResponse(responseCode = "400", description = "Invalid envelope/fields, missing or blank values, unsupported operations, malformed JSON, or unknown workflow event.",
                headers = @Header(name = WorkflowConstants.CORRELATION_ID_HEADER, description = "Server-generated ID for this HTTP attempt.",
                        schema = @Schema(type = "string", format = "uuid")),
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(implementation = WorkflowResponse.class),
                        examples = {
                            @ExampleObject(name = ApiErrorConstants.INVALID_REQUEST,
                                    value = """
                                        {
                                          "metadata": {"status":"FAILED","processedAt":"2026-11-04T06:00:00Z"},
                                          "payload": null,
                                          "error": {"code":"INVALID_REQUEST","description":"Provide a valid JSON request body."}
                                        }
                                        """)
                        })),
        @ApiResponse(responseCode = "404", description = "Workflow reference or active definition not found. Unknown routes return ENDPOINT_NOT_FOUND.",
                headers = @Header(name = WorkflowConstants.CORRELATION_ID_HEADER, description = "Server-generated ID for this HTTP attempt.",
                        schema = @Schema(type = "string", format = "uuid")),
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(implementation = WorkflowResponse.class),
                        examples = {
                            @ExampleObject(name = ApiErrorConstants.WORKFLOW_NOT_FOUND,
                                    value = """
                                        {
                                          "metadata": {"status":"FAILED","processedAt":"2026-11-04T06:00:00Z"},
                                          "payload": null,
                                          "error": {"code":"WORKFLOW_NOT_FOUND","description":"Workflow APP-101 was not found."}
                                        }
                                        """),
                            @ExampleObject(name = ApiErrorConstants.WORKFLOW_DEFINITION_NOT_FOUND,
                                    value = """
                                        {
                                          "metadata": {"status":"FAILED","processedAt":"2026-11-04T06:00:00Z"},
                                          "payload": null,
                                          "error": {"code":"WORKFLOW_DEFINITION_NOT_FOUND","description":"No active workflow definition exists for onboarding."}
                                        }
                                        """),
                            @ExampleObject(name = ApiErrorConstants.ENDPOINT_NOT_FOUND,
                                    value = """
                                        {
                                          "metadata": {"status":"FAILED","processedAt":"2026-11-04T06:00:00Z"},
                                          "payload": null,
                                          "error": {"code":"ENDPOINT_NOT_FOUND","description":"Endpoint not found."}
                                        }
                                        """)
                        })),
        @ApiResponse(responseCode = "409", description = "Workflow already exists, event is not allowed in the current state, retry inputs conflict, or the key is already processing.",
                headers = @Header(name = WorkflowConstants.CORRELATION_ID_HEADER, description = "Server-generated ID for this HTTP attempt.",
                        schema = @Schema(type = "string", format = "uuid")),
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(implementation = WorkflowResponse.class),
                        examples = {
                            @ExampleObject(name = ApiErrorConstants.WORKFLOW_ALREADY_EXISTS,
                                    value = """
                                        {
                                          "metadata": {"status":"FAILED","processedAt":"2026-11-04T06:00:00Z"},
                                          "payload": null,
                                          "error": {"code":"WORKFLOW_ALREADY_EXISTS","description":"This referenceId already has a workflow."}
                                        }
                                        """),
                            @ExampleObject(name = ApiErrorConstants.EVENT_NOT_ALLOWED,
                                    value = """
                                        {
                                          "metadata": {"status":"FAILED","processedAt":"2026-11-04T06:00:00Z"},
                                          "payload": null,
                                          "error": {"code":"EVENT_NOT_ALLOWED","description":"VERIFY_OTP is not allowed while the workflow is COMPLETED."}
                                        }
                                        """),
                            @ExampleObject(name = ApiErrorConstants.IDEMPOTENCY_IN_PROGRESS,
                                    value = """
                                        {"metadata":{"status":"FAILED","processedAt":"2026-11-04T06:00:00Z"},
                                         "payload":null,"error":{"code":"IDEMPOTENCY_IN_PROGRESS",
                                         "description":"This command is processing or requires recovery. Retry later with the same idempotency key."}}
                                        """),
                            @ExampleObject(name = ApiErrorConstants.IDEMPOTENCY_CONFLICT,
                                    value = """
                                        {
                                          "metadata": {"status":"FAILED","processedAt":"2026-11-04T06:00:00Z"},
                                          "payload": null,
                                          "error": {"code":"IDEMPOTENCY_CONFLICT","description":"This idempotencyKey is bound to different request inputs."}
                                        }
                                        """)
                        })),
        @ApiResponse(responseCode = "405", description = "Unsupported HTTP method; use POST.",
                headers = @Header(name = WorkflowConstants.CORRELATION_ID_HEADER, description = "Server-generated ID for this HTTP attempt.",
                        schema = @Schema(type = "string", format = "uuid")),
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(implementation = WorkflowResponse.class),
                        examples = {
                            @ExampleObject(name = ApiErrorConstants.INVALID_REQUEST,
                                    value = """
                                        {
                                          "metadata": {"status":"FAILED","processedAt":"2026-11-04T06:00:00Z"},
                                          "payload": null,
                                          "error": {"code":"INVALID_REQUEST","description":"Use POST /api/workflows."}
                                        }
                                        """)
                        })),
        @ApiResponse(responseCode = "415", description = "Unsupported Content-Type.",
                headers = @Header(name = WorkflowConstants.CORRELATION_ID_HEADER, description = "Server-generated ID for this HTTP attempt.",
                        schema = @Schema(type = "string", format = "uuid")),
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(implementation = WorkflowResponse.class),
                        examples = {
                            @ExampleObject(name = ApiErrorConstants.INVALID_REQUEST,
                                    value = """
                                        {
                                          "metadata": {"status":"FAILED","processedAt":"2026-11-04T06:00:00Z"},
                                          "payload": null,
                                          "error": {"code":"INVALID_REQUEST","description":"Use Content-Type application/json."}
                                        }
                                        """)
                        })),
        @ApiResponse(responseCode = "503", description = "Redis is unavailable or idempotency ownership was lost. Retry with the same key; do not create a new key.",
                headers = @Header(name = WorkflowConstants.CORRELATION_ID_HEADER, description = "HTTP attempt correlation ID.",
                        schema = @Schema(type = "string", format = "uuid")),
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(implementation = WorkflowResponse.class),
                        examples = @ExampleObject(name = ApiErrorConstants.IDEMPOTENCY_UNAVAILABLE,
                                value = """
                                    {"metadata":{"status":"FAILED","processedAt":"2026-11-04T06:00:00Z"},
                                     "payload":null,"error":{"code":"IDEMPOTENCY_UNAVAILABLE",
                                     "description":"Idempotency storage is unavailable. Retry with the same idempotency key."}}
                                    """))),
        @ApiResponse(responseCode = "500", description = "Unexpected database, persistence, action or execution failure. Mutation rolls back; details are logged server-side.",
                headers = @Header(name = WorkflowConstants.CORRELATION_ID_HEADER, description = "Server-generated ID for this HTTP attempt.",
                        schema = @Schema(type = "string", format = "uuid")),
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(implementation = WorkflowResponse.class),
                        examples = {
                            @ExampleObject(name = ApiErrorConstants.INTERNAL_ERROR,
                                    value = """
                                        {
                                          "metadata": {"status":"FAILED","processedAt":"2026-11-04T06:00:00Z"},
                                          "payload": null,
                                          "error": {"code":"INTERNAL_ERROR","description":"An unexpected error occurred."}
                                        }
                                        """)
                        }))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkflowResponse> processWorkflowCommand(@RequestBody WorkflowRequest workflowRequest, HttpServletRequest httpRequest) {
        WorkflowCommandMetadata requestMetadata = WorkflowRequestMetadataFunction.EXTRACT.apply(workflowRequest);
        httpRequest.setAttribute(RequestAttributeConstants.METADATA_ATTRIBUTE, requestMetadata);
        if (requestMetadata != null) {
            MDC.put(ApiPropertyConstants.REQUEST_ID, LogValueSanitizerFunction.SANITIZE.apply(requestMetadata.requestId()));
            MDC.put(ApiPropertyConstants.REFERENCE_ID, LogValueSanitizerFunction.SANITIZE.apply(requestMetadata.referenceId()));
            log.info("workflow_command_received operation={} event={} referenceId={}",
                    LogValueSanitizerFunction.SANITIZE.apply(requestMetadata.operation()), LogValueSanitizerFunction.SANITIZE.apply(requestMetadata.event()),
                    LogValueSanitizerFunction.SANITIZE.apply(requestMetadata.referenceId()));
        }

        ResponseEntity<WorkflowResponse> workflowResponse = workflowCommandGateway.executeWorkflowCommand(workflowRequestValidator.validate(workflowRequest));
        httpRequest.setAttribute(RequestAttributeConstants.RESPONSE_ATTRIBUTE, workflowResponse.getBody());
        return workflowResponse;
    }
}
