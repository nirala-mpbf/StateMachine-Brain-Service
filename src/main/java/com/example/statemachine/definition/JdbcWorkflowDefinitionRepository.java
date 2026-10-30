package com.example.statemachine.definition;

import com.example.statemachine.definition.WorkflowDefinition.ActionBinding;
import com.example.statemachine.definition.WorkflowDefinition.DeferredEvent;
import com.example.statemachine.definition.WorkflowDefinition.State;
import com.example.statemachine.definition.WorkflowDefinition.Transition;
import com.example.statemachine.exception.WorkflowFailure;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcWorkflowDefinitionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcWorkflowDefinitionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String findLatestActiveDefinitionId(String workflowKey) {
        final String sql = """
                SELECT definition_id
                FROM workflow_brain.workflow_definition
                WHERE workflow_key = :workflowKey AND status = 'ACTIVE'
                ORDER BY version DESC
                LIMIT 1
                """;

        final Map<String, ?> parameters = Map.of("workflowKey", workflowKey);

        final List<String> definitionIds = jdbcTemplate.query(sql, parameters, (resultSet, rowNum) -> {
            return resultSet.getString("definition_id");
        });

        if (definitionIds.isEmpty()) {
            throw new WorkflowFailure(
                    HttpStatus.NOT_FOUND,
                    "WORKFLOW_DEFINITION_NOT_FOUND",
                    "No active workflow definition exists for " + workflowKey + "."
            );
        }

        return definitionIds.getFirst();
    }

    public WorkflowDefinition loadWorkflowDefinition(String definitionId) {
        final Map<String, ?> parameters = Map.of("definitionId", definitionId);

        final DefinitionInfo definitionInfo = loadDefinitionInfo(definitionId, parameters);
        final Map<String, List<ActionBinding>> stateActions = loadStateActions(parameters);
        final List<State> states = loadStates(parameters, stateActions);
        final Map<String, List<String>> transitionActions = loadTransitionActions(parameters);
        final List<Transition> transitions = loadTransitions(parameters, transitionActions);
        final List<DeferredEvent> deferredEvents = loadDeferredEvents(parameters);

        return new WorkflowDefinition(
                definitionId,
                definitionInfo.workflowKey(),
                definitionInfo.version(),
                states,
                transitions,
                deferredEvents
        );
    }

    private DefinitionInfo loadDefinitionInfo(String definitionId, Map<String, ?> parameters) {
        final String sql = """
                SELECT workflow_key, version
                FROM workflow_brain.workflow_definition
                WHERE definition_id = :definitionId
                """;

        final List<DefinitionInfo> definitions = jdbcTemplate.query(sql, parameters, (resultSet, rowNum) -> {
            return new DefinitionInfo(
                    resultSet.getString("workflow_key"),
                    resultSet.getInt("version")
            );
        });

        if (definitions.isEmpty()) {
            throw new IllegalStateException("Workflow definition not found: " + definitionId);
        }

        return definitions.getFirst();
    }


    private Map<String, List<ActionBinding>> loadStateActions(Map<String, ?> parameters) {

        String sql = """
            SELECT state_code, action_code, phase, execution_order
            FROM workflow_brain.workflow_state_action
            WHERE definition_id = :definitionId
            ORDER BY state_code, phase, execution_order
            """;

        Map<String, List<ActionBinding>> actionsByState = new HashMap<>();

        RowCallbackHandler rowHandler = resultSet -> {

            String stateCode = resultSet.getString("state_code");
            ActionBinding actionBinding =
                    new ActionBinding(
                        resultSet.getString("action_code"),
                        resultSet.getString("phase"),
                        resultSet.getInt("execution_order")
                    );

            List<ActionBinding> stateActions = actionsByState.computeIfAbsent(stateCode, key -> new ArrayList<>());
            stateActions.add(actionBinding);
        };

        jdbcTemplate.query(sql, parameters, rowHandler);

        return actionsByState;
    }

    private List<State> loadStates(Map<String, ?> parameters, Map<String, List<ActionBinding>> actionsByState) {
        final String sql = """
                SELECT state_code, initial_state, terminal_state
                FROM workflow_brain.workflow_state
                WHERE definition_id = :definitionId
                ORDER BY state_code
                """;

        List<State> stateList = jdbcTemplate.query(sql, parameters, (resultSet, rowNum) -> {
            final String stateCode = resultSet.getString("state_code");
            final List<ActionBinding> actions = List.copyOf(actionsByState.getOrDefault(stateCode, List.of()));

            return new State(
                    stateCode,
                    resultSet.getBoolean("initial_state"),
                    resultSet.getBoolean("terminal_state"),
                    actions
            );
        });
        return stateList;
    }


    private Map<String, List<String>> loadTransitionActions(Map<String, ?> parameters) {

        String sql = """
            SELECT transition_action.transition_id, transition_action.action_code
            FROM workflow_brain.workflow_transition_action transition_action
            inner join workflow_brain.workflow_transition workflow_transition ON workflow_transition.transition_id = transition_action.transition_id
            WHERE workflow_transition.definition_id = :definitionId
            ORDER BY transition_action.transition_id, transition_action.execution_order
            """;

        Map<String, List<String>> actionsByTransition = new HashMap<>();

        RowCallbackHandler rowHandler = resultSet -> {
            String transitionId = resultSet.getString("transition_id");
            String actionCode = resultSet.getString("action_code");

            List<String> actionCodes = actionsByTransition.computeIfAbsent(transitionId, key -> new ArrayList<>());
            actionCodes.add(actionCode);
        };

        jdbcTemplate.query(sql, parameters, rowHandler);

        return actionsByTransition;
    }


    private List<Transition> loadTransitions(Map<String, ?> parameters, Map<String, List<String>> actionsByTransition) {
        final String sql = """
                SELECT transition_id, source_state, event_code, target_state, transition_kind, guard_code
                FROM workflow_brain.workflow_transition
                WHERE definition_id = :definitionId
                ORDER BY transition_id
                """;

        List<Transition> transitionList = jdbcTemplate.query(sql, parameters, (resultSet, rowNum) -> {
            String transitionId = resultSet.getString("transition_id");
            List<String> transitionActions = List.copyOf(actionsByTransition.getOrDefault(transitionId, List.of()));

            return new Transition(
                    transitionId,
                    resultSet.getString("source_state"),
                    resultSet.getString("event_code"),
                    resultSet.getString("target_state"),
                    resultSet.getString("transition_kind"),
                    resultSet.getString("guard_code"),
                    transitionActions
            );
        });

        return transitionList;
    }

    private List<DeferredEvent> loadDeferredEvents(Map<String, ?> parameters) {
        String sql = """
                SELECT state_code, event_code
                FROM workflow_brain.workflow_deferred_event
                WHERE definition_id = :definitionId
                ORDER BY state_code, event_code
                """;

        return jdbcTemplate.query(sql, parameters, (resultSet, rowNum) -> {
            return new DeferredEvent(
                    resultSet.getString("state_code"),
                    resultSet.getString("event_code")
            );
        });
    }

    private record DefinitionInfo(
            String workflowKey,
            int version
    ) {}
}
