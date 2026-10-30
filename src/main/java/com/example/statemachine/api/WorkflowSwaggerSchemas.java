package com.example.statemachine.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documentation-only models for springdoc / Swagger.
 *
 * <p>The real HTTP controller still receives JsonNode; WorkflowRequestValidator remains the runtime
 * authority on permitted fields and normalized defaults.
 */
public final class WorkflowSwaggerSchemas {
    private WorkflowSwaggerSchemas() {}

    // Java text blocks are compile-time constants and can be used in annotations.
    public static final String OPERATION_DESCRIPTION =
            """
            Persisted workflow brain: create a workflow instance, inspect its current state,
            or record a business event ALREADY completed by the consuming service.
            The tool does NOT verify OTPs, collect or update profiles, upload documents,
            modify Udyam registration information, or perform any business operation.

            AI TOOL SELECTION GUIDE:
            - "What's my onboarding status?", "What comes next?", "Is Udyam pending?",
              "Can I upload my Udyam?" -> GET_STATE using the real referenceId.
            - "Start onboarding" for a genuinely NEW reference -> CREATE.
            - "Verify OTP" -> consuming OTP service must verify successfully; ONLY AFTER
              success, SUBMIT_EVENT(event=OTP_VERIFIED).
            - "Submit my profile" -> consuming service must save the profile; ONLY AFTER
              success, SUBMIT_EVENT(event=PROFILE_SUBMITTED).
            - "Upload my bank statement" -> document service must store statement;
              ONLY AFTER success, SUBMIT_EVENT(event=ACCOUNT_STATEMENT_UPLOADED).
            - "Update my Udyam", "Upload Udyam document", "Attach Udyam certificate" ->
              the consuming Udyam/document service must finish the upload/update FIRST.
              ONLY AFTER confirmed success, SUBMIT_EVENT(event=UDYAM_DOCUMENT_UPLOADED).
              This endpoint CANNOT itself perform the requested Udyam update. If external
              success is unknown, GET_STATE may help assess readiness; never mark complete.
            - Never invent referenceId, state, or completed business actions.
            - If uncertain which transition is valid, call GET_STATE and inspect
              payload.availableTransitions. A guard may still reject a listed transition.

            SEEDED DATABASE WORKFLOW (workflowKey=onboarding, definition=onboarding-v1):
              OTP_VERIFICATION --OTP_VERIFIED--> PROFILE_DETAILS
              PROFILE_DETAILS --PROFILE_SUBMITTED--> ACCOUNT_STATEMENT_UPLOAD
              ACCOUNT_STATEMENT_UPLOAD --ACCOUNT_STATEMENT_UPLOADED--> UDYAM_DOCUMENT_UPLOAD
              UDYAM_DOCUMENT_UPLOAD --UDYAM_DOCUMENT_UPLOADED--> COMPLETED
            COMPLETED is terminal. Current seeded guard alwaysAllow does NOT verify the
            underlying business work. Future database definitions may have different events or deferred events.
            For a configured deferred event, eventOutcome=DEFERRED and pendingEvents
            holds it until a suitable later state; seeded onboarding-v1 has none.

            REQUEST FORMAT:
            Exactly two top-level JSON objects: metadata and payload. payload MUST be {}.
            CREATE requires operation, requestId, referenceId; optional workflowKey
            (defaults to onboarding) and idempotencyKey; NO event.
            GET_STATE requires operation, requestId, referenceId; NO event, workflowKey
            or idempotencyKey.
            SUBMIT_EVENT requires operation, requestId, referenceId and exact event;
            optional idempotencyKey; NO workflowKey.
            Strings are case-sensitive. requestId/idempotencyKey max 128 characters;
            referenceId/workflowKey/event max 100 characters, all nonblank.

            READS, AUDIT AND CORRELATION:
            GET_STATE reads the persisted state/context snapshot without a mutation row
            lock or executing state-machine actions. It does not create history records.
            Successfully applied events are stored in workflow_transition_history in the
            same transaction as workflow progress. Internal transitions and applied deferred
            events are recorded; rejected events and idempotency replays add no history.
            No history-reading API is exposed. Historical transitions from before deployment
            are not reconstructed. X-Correlation-Id is a server-generated response header
            for tracing this HTTP attempt; it is distinct from requestId and idempotencyKey.

            RETRIES AND RESPONSE:
            requestId is correlation only, and can change on retry. idempotencyKey is
            optional for mutations but strongly recommended. It is globally scoped and
            must be reused ONLY for the same operation/referenceId/event/workflowKey
            inputs. On a successful replay, the original status/payload/processedAt are
            returned, with current requestId and idempotencyReplayed=true. The original
            saved snapshot may be stale; use GET_STATE for up-to-date progress. Failed
            requests do not store a successful response; keys do not automatically expire.
            CREATE -> 201; GET_STATE and SUBMIT_EVENT -> 200.
            400 INVALID_REQUEST, 404 WORKFLOW_NOT_FOUND/WORKFLOW_DEFINITION_NOT_FOUND,
            409 WORKFLOW_ALREADY_EXISTS/EVENT_NOT_ALLOWED/GUARD_REJECTED/
            IDEMPOTENCY_CONFLICT, 500 INTERNAL_ERROR. Unsupported methods and content
            types return 405 and 415, respectively.
            """;

    @Schema(
            name = "EmptyWorkflowPayload",
            description =
                    "Required literal empty JSON object {}. No OTP, file data, profile data, Udyam"
                        + " fields, URLs or arbitrary properties may be sent.",
            type = "object",
            maxProperties = 0,
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record EmptyPayload() {}

    @Schema(
            name = "CreateWorkflowMetadata",
            description = "CREATE command metadata. No event field is permitted.",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CreateMetadata(
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            allowableValues = {"CREATE"},
                            description = "MUST be CREATE (case-sensitive).",
                            example = "CREATE")
                    String operation,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            minLength = 1,
                            maxLength = 128,
                            description =
                                    "New correlation ID for this request. It is NOT a deduplication"
                                        + " key.",
                            example = "req-create-1")
                    String requestId,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            minLength = 1,
                            maxLength = 100,
                            description =
                                    "Unique application/workflow instance ID supplied by the"
                                        + " consuming service. Do not invent one for an existing"
                                        + " application.",
                            example = "APP-101")
                    String referenceId,
            @Schema(
                            defaultValue = "onboarding",
                            minLength = 1,
                            maxLength = 100,
                            description =
                                    "Optional ONLY on CREATE. Active database workflow key;"
                                        + " omission defaults to onboarding.",
                            example = "onboarding")
                    String workflowKey,
            @Schema(
                            minLength = 1,
                            maxLength = 128,
                            description =
                                    "Optional stable globally unique retry key for THIS CREATE."
                                        + " Reuse only for identical CREATE retries; never for a"
                                        + " distinct event.",
                            example = "APP-101-create-v1")
                    String idempotencyKey) {}

    @Schema(
            name = "GetStateWorkflowMetadata",
            description = "GET_STATE metadata. Do NOT send event, workflowKey, or idempotencyKey.",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record GetStateMetadata(
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            allowableValues = {"GET_STATE"},
                            description = "MUST be GET_STATE (case-sensitive).",
                            example = "GET_STATE")
                    String operation,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            minLength = 1,
                            maxLength = 128,
                            description = "Correlation ID for this read.",
                            example = "req-state-1")
                    String requestId,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            minLength = 1,
                            maxLength = 100,
                            description =
                                    "Existing application reference to inspect. Must be a real"
                                        + " known instance.",
                            example = "APP-101")
                    String referenceId) {}

    @Schema(
            name = "SubmitEventWorkflowMetadata",
            description =
                    "SUBMIT_EVENT metadata. ONLY send when the corresponding external business"
                        + " action is confirmed complete. Do NOT send workflowKey.",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record SubmitEventMetadata(
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            allowableValues = {"SUBMIT_EVENT"},
                            description = "MUST be SUBMIT_EVENT (case-sensitive).",
                            example = "SUBMIT_EVENT")
                    String operation,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            minLength = 1,
                            maxLength = 128,
                            description =
                                    "Unique correlation ID for this call; NOT for deduplication.",
                            example = "req-udyam-1")
                    String requestId,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            minLength = 1,
                            maxLength = 100,
                            description = "Existing application reference whose state may advance.",
                            example = "APP-101")
                    String referenceId,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            minLength = 1,
                            maxLength = 100,
                            description =
                                    "Exact case-sensitive event code. Seeded onboarding-v1"
                                        + " mappings: OTP_VERIFIED: verified OTP, when at"
                                        + " OTP_VERIFICATION, -> PROFILE_DETAILS;"
                                        + " PROFILE_SUBMITTED: saved profile, when at"
                                        + " PROFILE_DETAILS, -> ACCOUNT_STATEMENT_UPLOAD;"
                                        + " ACCOUNT_STATEMENT_UPLOADED: statement was uploaded,"
                                        + " when at ACCOUNT_STATEMENT_UPLOAD, ->"
                                        + " UDYAM_DOCUMENT_UPLOAD; UDYAM_DOCUMENT_UPLOADED: Udyam"
                                        + " registration document was uploaded/updated by the"
                                        + " consuming service, when at UDYAM_DOCUMENT_UPLOAD, ->"
                                        + " COMPLETED. This is NOT a command to perform an Udyam"
                                        + " upload. Other database definitions may introduce other"
                                        + " event codes; GET_STATE provides availableTransitions.",
                            example = "UDYAM_DOCUMENT_UPLOADED")
                    String event,
            @Schema(
                            minLength = 1,
                            maxLength = 128,
                            description =
                                    "Optional retry key bound to THIS event and reference; repeat"
                                        + " unchanged only for retries of the same logical"
                                        + " completion event.",
                            example = "APP-101-udyam-upload-v1")
                    String idempotencyKey) {}

    @Schema(
            name = "CreateWorkflowRequest",
            description = "CREATE a fresh persisted workflow; payload must be {}.",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CreateRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CreateMetadata metadata,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EmptyPayload payload) {}

    @Schema(
            name = "GetWorkflowStateRequest",
            description = "GET_STATE read-only inspection; payload must be {}.",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record GetStateRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) GetStateMetadata metadata,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EmptyPayload payload) {}

    @Schema(
            name = "SubmitWorkflowEventRequest",
            description =
                    "SUBMIT_EVENT records a completed external business milestone, not the business"
                        + " action itself; payload must be {}.",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record SubmitEventRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SubmitEventMetadata metadata,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EmptyPayload payload) {}
}
