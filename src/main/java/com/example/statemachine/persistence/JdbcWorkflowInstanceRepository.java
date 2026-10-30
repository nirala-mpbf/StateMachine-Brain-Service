package com.example.statemachine.persistence;

import com.example.statemachine.exception.WorkflowFailure;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public class JdbcWorkflowInstanceRepository {
    public record Instance(
            String referenceId,
            String definitionId,
            String state,
            String context,
            int formatVersion) {}

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcWorkflowInstanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String reference, String definition, String state) {

        final String sql =   """
                            INSERT INTO workflow_brain.workflow_instances (reference_id, definition_id, current_state)
                            VALUES (:reference, :definition, :state)
                            """;
        Map<String,Object> params = Map.of("reference", reference, "definition", definition, "state", state);

        jdbc.update(sql, params);
    }

    public Instance findByReference(String reference) {
        return readInstance(reference, false);
    }

    public Instance lockByReference(String reference) {
        return readInstance(reference, true);
    }

    private Instance readInstance(String reference, boolean lock) {
        final String sql = lock
                ? """
                  SELECT reference_id, definition_id, current_state, machine_context, context_format_version
                  FROM workflow_brain.workflow_instances WHERE reference_id = :reference
                  FOR UPDATE
                  """
                : """
                  SELECT reference_id, definition_id, current_state, machine_context, context_format_version
                  FROM workflow_brain.workflow_instances WHERE reference_id = :reference
                  """;
        Map<String, Object> params = Map.of("reference", reference);

        return jdbc.query( sql, params, (rs, row) ->
                                    new Instance(
                                        rs.getString("reference_id"),
                                        rs.getString("definition_id"),
                                        rs.getString("current_state"),
                                        rs.getString("machine_context"),
                                        rs.getInt("context_format_version")
                                    )
                )
                .stream()
                .findFirst()
                .orElseThrow( () -> new WorkflowFailure( HttpStatus.NOT_FOUND, "WORKFLOW_NOT_FOUND",
                            "Workflow " + reference + " was not found.")
                );
    }

    public void saveContext(String reference, String state, String json) {
        final String sql = """
                        UPDATE workflow_brain.workflow_instances SET current_state = :state, machine_context = :context,
                        context_format_version = 1, updated_at = CURRENT_TIMESTAMP WHERE reference_id = :reference
                        """;

        Map<String, Object> params = Map.of("reference", reference, "state", state, "context", json);
        int updated = jdbc.update(sql, params);

        if (updated != 1){
            throw new IllegalStateException("Workflow disappeared while persisting context.");
        }
    }
}
