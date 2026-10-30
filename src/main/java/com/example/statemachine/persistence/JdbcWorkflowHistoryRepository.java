package com.example.statemachine.persistence;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * Append-only records of applied events; participates in the caller's workflow transaction.
 */
@Repository
public class JdbcWorkflowHistoryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcWorkflowHistoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordTransition(String referenceId, String definitionId, String sourceState,
                                 String event, String targetState, String requestId, String idempotencyKey) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("referenceId", referenceId);
        parameters.put("definitionId", definitionId);
        parameters.put("sourceState", sourceState);
        parameters.put("event", event);
        parameters.put("targetState", targetState);
        parameters.put("requestId", requestId);
        parameters.put("idempotencyKey", idempotencyKey);


        final String sql =
                """
                INSERT INTO workflow_brain.workflow_transition_history
                (reference_id, definition_id, source_state, event_code, target_state, request_id, idempotency_key)
                VALUES (:referenceId, :definitionId, :sourceState, :event, :targetState, :requestId, :idempotencyKey)
                """;

        jdbc.update(sql, parameters);
    }
}
