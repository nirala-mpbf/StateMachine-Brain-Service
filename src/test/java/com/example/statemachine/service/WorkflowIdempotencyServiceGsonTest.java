package com.example.statemachine.service;

import com.example.statemachine.config.GsonConfiguration;
import com.example.statemachine.dto.api.WorkflowCommandMetadata;
import com.example.statemachine.dto.api.WorkflowResponse;
import com.example.statemachine.dto.persistence.WorkflowIdempotencyClaim;
import com.example.statemachine.dto.workflow.WorkflowCommand;
import com.example.statemachine.enums.IdempotencyClaimOutcome;
import com.example.statemachine.enums.WorkflowOperation;
import com.example.statemachine.repository.RedisWorkflowIdempotencyRepository;
import com.google.gson.Gson;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowIdempotencyServiceGsonTest {
    @Test
    void replaysPreviouslyStoredResponseWithOriginalTimestampAndNewRequestId() {
        String savedResponse = """
                {"metadata":{"operation":"CREATE","requestId":"original-request","referenceId":"APP-101",
                "idempotencyKey":"key-1","workflowKey":"onboarding","status":"SUCCESS",
                "processedAt":"2026-11-04T06:00:00Z","idempotencyReplayed":false},
                "payload":{"referenceId":"APP-101","workflowKey":"onboarding","definitionVersion":1,
                "currentState":"OTP_VERIFICATION","nextEvent":"VERIFY_OTP","nextState":"PROFILE_DETAILS",
                "completed":false,"availableTransitions":[{"event":"VERIFY_OTP","nextState":"PROFILE_DETAILS"}]}}
                """;
        RedisWorkflowIdempotencyRepository repository = new RedisWorkflowIdempotencyRepository(null) {
            @Override
            public WorkflowIdempotencyClaim claim(String key, String fingerprint, String ownerToken) {
                return new WorkflowIdempotencyClaim(IdempotencyClaimOutcome.REPLAY, savedResponse, 201);
            }
        };
        Gson gson = new GsonConfiguration().gson();
        WorkflowIdempotencyService service = new WorkflowIdempotencyService(repository, null, gson);
        WorkflowCommand command = new WorkflowCommand(new WorkflowCommandMetadata(
                "CREATE", "retry-request", "APP-101", null, "key-1", "onboarding"), WorkflowOperation.CREATE);
        var response = service.execute(command, "fingerprint");
        assertEquals(201, response.getStatusCode().value());
        WorkflowResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("retry-request", body.metadata().requestId());
        assertEquals(Instant.parse("2026-11-04T06:00:00Z"), body.metadata().processedAt());
        assertTrue(body.metadata().idempotencyReplayed());
        assertEquals("OTP_VERIFICATION", body.payload().currentState());
        assertEquals(body, gson.fromJson(gson.toJson(body), WorkflowResponse.class));
    }
}
