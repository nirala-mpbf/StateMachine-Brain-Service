package com.example.statemachine.api;

import com.example.statemachine.api.WorkflowApi.Command;
import com.example.statemachine.api.WorkflowApi.Operation;
import com.example.statemachine.api.WorkflowApi.RequestMetadata;
import com.example.statemachine.exception.WorkflowFailure;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.util.Set;

@Component
public class WorkflowRequestValidator {

    public Command validate(JsonNode body) {
        object(body, "request");
        fields(body, Set.of("metadata", "payload"), "request");

        JsonNode metadata = body.get("metadata");
        object(metadata, "metadata");

        fields(metadata, Set.of("operation", "requestId", "referenceId", "event", "idempotencyKey", "workflowKey"), "metadata");

        String name = validateAndGet(metadata, "operation", 32);
        validateAndGet(metadata, "requestId", 128);
        validateAndGet(metadata, "referenceId", 100);

        Operation operation;
        try {
            operation = Operation.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw WorkflowFailure.invalid("operation must be CREATE, GET_STATE or SUBMIT_EVENT.");
        }

        if (operation == Operation.SUBMIT_EVENT)
            validateAndGet(metadata, "event", 100);
        else
            absent(metadata, "event");

        if (operation == Operation.CREATE) {
            if (metadata.has("workflowKey"))
                validateAndGet(metadata, "workflowKey", 100);
        } else absent(metadata, "workflowKey");

        if (metadata.has("idempotencyKey")) {
            if (operation == Operation.GET_STATE) absent(metadata, "idempotencyKey");
            validateAndGet(metadata, "idempotencyKey", 128);
        }

        object(body.get("payload"), "payload");
        fields(body.get("payload"), Set.of(), "payload");

        RequestMetadata recovered = extractMetadataForErrorResponse(body);

        return new Command(new RequestMetadata(recovered.operation(), recovered.requestId(), recovered.referenceId(), recovered.event(), recovered.idempotencyKey(), operation == Operation.CREATE ? (recovered.workflowKey() == null ? "onboarding" : recovered.workflowKey()) : null), operation);
    }

    public RequestMetadata extractMetadataForErrorResponse(JsonNode body) {

        JsonNode m = (body == null) ? null : body.get("metadata");

        if (m == null || !m.isObject()) return null;
        return new RequestMetadata(text(m, "operation", 32), text(m, "requestId", 128), text(m, "referenceId", 100), text(m, "event", 100), text(m, "idempotencyKey", 128), text(m, "workflowKey", 100));
    }

    private String text(JsonNode m, String name, int limit) {
        JsonNode value = m.get(name);
        return (value != null && value.isString() && value.asString().length() <= limit) ? value.asString() : null;
    }

    private String validateAndGet(JsonNode m, String name, int limit) {

        String value = text(m, name, limit);

        if (value == null || value.isBlank())
            throw WorkflowFailure.invalid("metadata." + name + " must be a nonblank string of at most " + limit + " characters.");
        return value;
    }

    private void absent(JsonNode m, String name) {
        if (m.has(name)) {
            throw WorkflowFailure.invalid("metadata." + name + " is not allowed for this operation.");
        }
    }

    private void object(JsonNode node, String name) {
        if (node == null || !node.isObject()) throw WorkflowFailure.invalid(name + " must be a JSON object.");
    }

    private void fields(JsonNode node, Set<String> allowed, String path) {
        node.propertyNames().forEach(name -> {
            if (!allowed.contains(name)) throw WorkflowFailure.invalid("Unsupported field: " + path + "." + name);
        });
    }
}
