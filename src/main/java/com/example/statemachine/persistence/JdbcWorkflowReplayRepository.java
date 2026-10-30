package com.example.statemachine.persistence;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public class JdbcWorkflowReplayRepository {
    public record Replay(String fingerprint, String response, Integer status) {}

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcWorkflowReplayRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(String key) {
        final String sql = """
                SELECT COUNT(*) > 0
                FROM workflow_brain.workflow_request_replay
                WHERE idempotency_key = :key
                """;
        Map<String, Object> params = Map.of("key", key);

        return Boolean.TRUE.equals(jdbc.queryForObject(sql, params, Boolean.class));
    }

    public void reserve(String key, String fingerprint) {
        final String sql = """
                INSERT INTO workflow_brain.workflow_request_replay (idempotency_key, request_fingerprint)
                VALUES (:key, :fingerprint)
                """;
        Map<String, Object> params = Map.of("key", key, "fingerprint", fingerprint);

        jdbc.update(sql, params);
    }

    public Replay lock(String key) {
        final String sql = """
                SELECT request_fingerprint, response_json, http_status
                FROM workflow_brain.workflow_request_replay
                WHERE idempotency_key = :key FOR UPDATE
                """;
        Map<String, Object> params = Map.of("key", key);

        return jdbc.queryForObject(sql, params, (rs, row) ->
                new Replay(
                        rs.getString("request_fingerprint"),
                        rs.getString("response_json"),
                        (Integer) rs.getObject("http_status")));
    }

    public void updateReplay(String key, String response, int status) {
        final String sql = """
                UPDATE workflow_brain.workflow_request_replay SET response_json = :response,
                http_status = :status, completed_at = CURRENT_TIMESTAMP WHERE idempotency_key = :key
                """;
        Map<String, Object> params = Map.of("key", key, "response", response, "status", status);

        jdbc.update(sql, params);
    }
}
