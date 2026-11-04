package com.example.statemachine.statemachine;

import com.example.statemachine.command.WorkflowStepCommand;
import com.example.statemachine.constants.WorkflowBehaviorConstants;
import com.example.statemachine.constants.WorkflowConstants;
import com.example.statemachine.dto.workflow.WorkflowStepContext;
import com.example.statemachine.strategy.WorkflowStepStrategy;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import lombok.AllArgsConstructor;
import com.example.statemachine.constants.WorkflowContextConstants;
import com.example.statemachine.enums.LogValueSanitizerFunction;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Actions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Explicit strategy registry: database action names select code; no expressions are evaluated. */
@Component
@Slf4j
@AllArgsConstructor
public class WorkflowBehaviorRegistry {
    private final List<WorkflowStepStrategy> stepStrategies;
    private final Map<String, WorkflowStepStrategy> strategiesByName = new HashMap<>();


    private final Map<String, Function< StateContext<String, String>, Mono<Void> > > actions =
            Map.of(
                    WorkflowBehaviorConstants.RECORD_STATE_ENTRY,
                        Actions.from( stateContext -> {
                                    stateContext.getExtendedState()
                                            .getVariables()
                                            .put(WorkflowContextConstants.LAST_ENTERED_STATE, stateContext.getTarget().getId());
                                    log.debug("workflow_state_entered state={}", LogValueSanitizerFunction.SANITIZE.apply(stateContext.getTarget().getId()));

                                }
                        ),

                    WorkflowBehaviorConstants.RECORD_STATE_EXIT,
                        Actions.from(stateContext -> {
                                    stateContext.getExtendedState()
                                            .getVariables()
                                            .put(WorkflowContextConstants.LAST_EXITED_STATE, stateContext.getSource().getId());
                                    log.debug("workflow_state_exited state={}", LogValueSanitizerFunction.SANITIZE.apply(stateContext.getSource().getId()));
                                }
                        ),

                    WorkflowBehaviorConstants.RECORD_TRANSITION,
                        Actions.from( stateContext -> {
                                    stateContext.getExtendedState()
                                            .getVariables()
                                            .put(WorkflowContextConstants.LAST_PROCESSED_EVENT, stateContext.getEvent());
                                    log.debug("workflow_transition_action event={}", LogValueSanitizerFunction.SANITIZE.apply(stateContext.getEvent()));
                                }
                        ),

                    WorkflowBehaviorConstants.OBSERVE_STATE,
                        Actions.from( stateContext -> log.debug("workflow_state_activity definition={}", LogValueSanitizerFunction.SANITIZE.apply(stateContext.getStateMachine().getId())))
            );

    @PostConstruct
    public void registerStepStrategies() {
        for (WorkflowStepStrategy strategy : stepStrategies) {
            String actionName = strategy.actionName();
            if (actionName == null || actionName.isBlank()) {
                throw new IllegalStateException("Workflow step action name must be nonblank.");
            }
            if (actions.containsKey(actionName) || strategiesByName.putIfAbsent(actionName, strategy) != null) {
                throw new IllegalStateException("Duplicate workflow action: " + actionName);
            }
        }
    }

    public Function<StateContext<String, String>, Mono<Void>> resolveActionByName(String actionName) {
        Function<StateContext<String, String>, Mono<Void>> action = actions.get(actionName);
        if (action != null) {
            return action;
        }

        WorkflowStepStrategy strategy = strategiesByName.get(actionName);
        if (strategy == null) {
            throw new IllegalStateException("Unregistered workflow action: " + actionName);
        }

        return stateContext -> Mono.fromCallable(() -> {
            WorkflowStepContext stepContext = stateContext.getMessageHeaders().get(
                    WorkflowConstants.WORKFLOW_STEP_CONTEXT_HEADER, WorkflowStepContext.class);
            if (stepContext == null) {
                throw new IllegalStateException("Business step requires a submitted command context: " + actionName);
            }
            new WorkflowStepCommand(strategy, stepContext).execute();
            return true;
        }).then();
    }
}
