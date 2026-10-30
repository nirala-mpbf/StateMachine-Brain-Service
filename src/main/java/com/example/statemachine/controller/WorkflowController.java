package com.example.statemachine.controller;

import com.example.statemachine.api.WorkflowApi;
import com.example.statemachine.api.WorkflowRequestValidator;
import com.example.statemachine.api.WorkflowSwaggerSchemas;
import com.example.statemachine.exception.WorkflowExceptionHandler;
import com.example.statemachine.logging.WorkflowRequestLoggingFilter;
import com.example.statemachine.service.WorkflowCommandGateway;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/workflows")
@Tag(name = "Workflow brain", description = "Create an onboarding workflow, inspect persisted progress, or record a completed OTP/profile/account-statement/Udyam milestone operations or upload documents.")
public class WorkflowController {

    private final WorkflowRequestValidator requestValidator;
    private final WorkflowCommandGateway commandGateway;

    public WorkflowController(WorkflowRequestValidator requestValidator, WorkflowCommandGateway commandGateway) {
        this.requestValidator = requestValidator;
        this.commandGateway = commandGateway;
    }

    @Operation(
            operationId = "processWorkflowCommand", summary = "Create onboarding, inspect its state, or record complete OTP/profile/statement/Udyam steps",
            description = WorkflowSwaggerSchemas.OPERATION_DESCRIPTION,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Exactly one CREATE, GET_STATE or SUBMIT_EVENT envelope; payload MUST be an empty {} object. Pick the matching request example below",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema( oneOf = {
                                    WorkflowSwaggerSchemas.CreateRequest.class,
                                    WorkflowSwaggerSchemas.GetStateRequest.class,
                                    WorkflowSwaggerSchemas.SubmitEventRequest.class
                            }),
                            examples = {
                                    @ExampleObject(
                                            name = "01 - Create onboarding instance",
                                            summary = "CREATE (201): new reference, initial state OTP_VERIFICATION",
                                            description = "For a new application only workflowKey=onboarding can be omitted. idempotencyKey is recommended for safe retries.",
                                            value = """
                                                    {"metadata":{"operation":"CREATE","requestId":"req-create-101","referenceId":"APP-101","workflowKey":"onboarding","idempotencyKey":"APP-101-create-v1"},"payload":{}}
                                                """
                                    ),
                                    @ExampleObject(
                                            name = "02 - Get current state",
                                            summary = "GET_STATE (200): inspect progress and availableTransitions",
                                            description = "Read-only. Do not include event," + " workflowKey or" + " idempotencyKey.",
                                            value = """
                                                    {"metadata":{"operation":"GET_STATE","requestId":"req-state-101","referenceId":"APP-101"},"payload":{}}
                                            """
                                    ),
                                    @ExampleObject(
                                            name = "03 - OTP verified",
                                            summary = "SUBMIT_EVENT: OTP_VERIFICATION ->" + " PROFILE_DETAILS",
                                            description = "Call ONLY after OTP has been" + " verified by consuming" + " business service.",
                                            value = """
                                                {"metadata":{"operation":"SUBMIT_EVENT","requestId":"req-otp-101","referenceId":"APP-101","event":"OTP_VERIFIED","idempotencyKey":"APP-101-otp-v1"},"payload":{}}
                                                """
                                    ),
                                    @ExampleObject(
                                            name = "04 - Profile submitted",
                                            summary = "SUBMIT_EVENT: PROFILE_DETAILS ->" + " ACCOUNT_STATEMENT_UPLOAD",
                                            description = "Call ONLY after profile details" + " have been stored/submitted" + " successfully elsewhere.",
                                            value =
                                                """
                                                {"metadata":{"operation":"SUBMIT_EVENT","requestId":"req-profile-101","referenceId":"APP-101","event":"PROFILE_SUBMITTED","idempotencyKey":"APP-101-profile-v1"},"payload":{}}
                                                """
                                    ),
                                    @ExampleObject(
                                            name = "05 - Account statement uploaded",
                                            summary = "SUBMIT_EVENT:" + " ACCOUNT_STATEMENT_UPLOAD ->" + " UDYAM_DOCUMENT_UPLOAD",
                                            description = "Call ONLY after successful bank" + " account statement upload" + " elsewhere; no file payload" + " here.",
                                            value = """
                                            {"metadata":{"operation":"SUBMIT_EVENT","requestId":"req-statement-101","referenceId":"APP-101","event":"ACCOUNT_STATEMENT_UPLOADED","idempotencyKey":"APP-101-statement-v1"},"payload":{}}
                                            """
                                    ),
                                    @ExampleObject(
                                            name = "06 - Udyam document uploaded",
                                            summary = "SUBMIT_EVENT:" + " UDYAM_DOCUMENT_UPLOAD ->" + " COMPLETED",
                                            description = "Use when the user says 'update my" + " Udyam' ONLY AFTER the" + " consuming service has" + " successfully" + " uploaded/updated the Udyam" + " registration document. This" + " workflow tool does NOT do" + " the upload or update" + " itself.",
                                            value = """
                                            {"metadata":{"operation":"SUBMIT_EVENT","requestId":"req-udyam-101","referenceId":"APP-101","event":"UDYAM_DOCUMENT_UPLOADED","idempotencyKey":"APP-101-udyam-v1"},"payload":{}}
                                            """
                                    )
                            }
                    )
            )
    )
    @ApiResponse(headers = @Header(name = "X-Correlation-Id", description = "Server-generated HTTP correlation ID for log tracing; distinct" + " from requestId and idempotencyKey.", schema = @Schema(type = "string", format = "uuid")), responseCode = "200", description = "GET_STATE read successfully or SUBMIT_EVENT applied/deferred. On idempotency" + " replay returns original successful snapshot, not necessarily latest" + " state.", content = @Content(schema = @Schema(implementation = WorkflowApi.Response.class)))
    @ApiResponse(headers = @Header(name = "X-Correlation-Id", description = "Server-generated HTTP correlation ID for log tracing; distinct" + " from requestId and idempotencyKey.", schema = @Schema(type = "string", format = "uuid")), responseCode = "201", description = "CREATE stored new workflow; seeded onboarding starts at OTP_VERIFICATION.", content = @Content(schema = @Schema(implementation = WorkflowApi.Response.class)))
    @ApiResponse(headers = @Header(name = "X-Correlation-Id", description = "Server-generated HTTP correlation ID for log tracing; distinct" + " from requestId and idempotencyKey.", schema = @Schema(type = "string", format = "uuid")), responseCode = "400", description = "INVALID_REQUEST: invalid envelope, unknown event, wrong/extra fields," + " non-empty payload.", content = @Content(schema = @Schema(implementation = WorkflowApi.Response.class)))
    @ApiResponse(headers = @Header(name = "X-Correlation-Id", description = "Server-generated HTTP correlation ID for log tracing; distinct" + " from requestId and idempotencyKey.", schema = @Schema(type = "string", format = "uuid")), responseCode = "404", description = "WORKFLOW_NOT_FOUND or WORKFLOW_DEFINITION_NOT_FOUND.", content = @Content(schema = @Schema(implementation = WorkflowApi.Response.class)))
    @ApiResponse(headers = @Header(name = "X-Correlation-Id", description = "Server-generated HTTP correlation ID for log tracing; distinct" + " from requestId and idempotencyKey.", schema = @Schema(type = "string", format = "uuid")), responseCode = "409", description = "WORKFLOW_ALREADY_EXISTS, EVENT_NOT_ALLOWED, GUARD_REJECTED or" + " IDEMPOTENCY_CONFLICT.", content = @Content(schema = @Schema(implementation = WorkflowApi.Response.class)))
    @ApiResponse(headers = @Header(name = "X-Correlation-Id", description = "Server-generated HTTP correlation ID for log tracing; distinct" + " from requestId and idempotencyKey.", schema = @Schema(type = "string", format = "uuid")), responseCode = "405", description = "Unsupported HTTP method (INVALID_REQUEST).", content = @Content(schema = @Schema(implementation = WorkflowApi.Response.class)))
    @ApiResponse(headers = @Header(name = "X-Correlation-Id", description = "Server-generated HTTP correlation ID for log tracing; distinct" + " from requestId and idempotencyKey.", schema = @Schema(type = "string", format = "uuid")), responseCode = "415", description = "Unsupported media type; Content-Type must be application/json" + " (INVALID_REQUEST).", content = @Content(schema = @Schema(implementation = WorkflowApi.Response.class)))
    @ApiResponse(headers = @Header(name = "X-Correlation-Id", description = "Server-generated HTTP correlation ID for log tracing; distinct" + " from requestId and idempotencyKey.", schema = @Schema(type = "string", format = "uuid")), responseCode = "500", description = "INTERNAL_ERROR: unexpected state-machine, action, serialization or database" + " failure.", content = @Content(schema = @Schema(implementation = WorkflowApi.Response.class)))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkflowApi.Response> handle(@RequestBody JsonNode body, @Parameter(hidden = true) HttpServletRequest request) {

        request.setAttribute(WorkflowExceptionHandler.METADATA_ATTRIBUTE, requestValidator.extractMetadataForErrorResponse(body));
        WorkflowRequestLoggingFilter.logCommand((WorkflowApi.RequestMetadata) request.getAttribute(WorkflowExceptionHandler.METADATA_ATTRIBUTE));

        ResponseEntity<WorkflowApi.Response> response = commandGateway.execute(requestValidator.validate(body));
        request.setAttribute(WorkflowRequestLoggingFilter.RESPONSE_ATTRIBUTE, response.getBody());

        return response;
    }
}
