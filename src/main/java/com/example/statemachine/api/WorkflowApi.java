package com.example.statemachine.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class WorkflowApi {

    private WorkflowApi() {
    }

    public enum Operation {
        CREATE, GET_STATE, SUBMIT_EVENT
    }

    public enum EventOutcome {
        APPLIED, DEFERRED
    }

    public record RequestMetadata(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"CREATE", "GET_STATE", "SUBMIT_EVENT"})
            String operation,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 128)
            String requestId,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
            String referenceId,

            @Schema(description = "Required only for SUBMIT_EVENT; defined by the instance's" + " workflow version", maxLength = 100)
            String event,

            @Schema(description = "Optional for mutations; forbidden for GET_STATE", minLength = 1, maxLength = 128)
            String idempotencyKey,

            @Schema(description = "Allowed only for CREATE; defaults to onboarding", minLength = 1, maxLength = 100)
            String workflowKey
    ) { }

    public record Request(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            RequestMetadata metadata,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Must be an empty object", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
            Map<String, Object> payload
    ) { }

    public record Command(RequestMetadata metadata, Operation operation) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResponseMetadata(
            String operation,
            String requestId,
            String referenceId,
            String event,
            String idempotencyKey,
            String workflowKey,
            String status,
            Instant processedAt,
            Boolean idempotencyReplayed
    ) { }

    public record AvailableTransition(
            String event,
            String nextState
    ) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkflowDetails(
            String referenceId,
            String workflowKey,
            int definitionVersion,
            String currentState,
            String nextEvent,
            String nextState,
            boolean completed,
            List<AvailableTransition> availableTransitions,
            EventOutcome eventOutcome,
            List<String> pendingEvents
    ) { }

    public record ApiError(
            String code,
            String description
    ) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            ResponseMetadata metadata,

            @JsonInclude(JsonInclude.Include.ALWAYS)
            WorkflowDetails payload,

            ApiError error
    ) { }
}
