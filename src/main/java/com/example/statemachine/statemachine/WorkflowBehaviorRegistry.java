package com.example.statemachine.statemachine;

import com.example.statemachine.util.WorkflowBehaviorNames;
import com.example.statemachine.util.WorkflowContextKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Actions;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Function;

/** Explicit strategy registry: database names select code; no expressions are evaluated. */
@Component
public class WorkflowBehaviorRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowBehaviorRegistry.class);

    // key is an action-name(String) and the value is executable Java behavior(Function<StateContext<S,E>, Mono<void>>)
    // A map whose keys are action names and whose values are functions that receive StateMachine execution context and return a completion signal
    private final Map<String, Function< StateContext<String, String>, Mono<Void> > > actions =
            Map.of(
                    WorkflowBehaviorNames.RECORD_STATE_ENTRY,
                        Actions.from( stateContext -> {
                                    stateContext.getExtendedState()
                                            .getVariables()
                                            .put(WorkflowContextKeys.LAST_ENTERED_STATE, stateContext.getTarget().getId());
                                    LOG.info("Action-{} = {}: {}", WorkflowBehaviorNames.RECORD_STATE_ENTRY, WorkflowContextKeys.LAST_ENTERED_STATE, stateContext.getTarget().getId());

                                }
                        ),

                    WorkflowBehaviorNames.RECORD_STATE_EXIT,
                        Actions.from(stateContext -> {
                                    stateContext.getExtendedState()
                                            .getVariables()
                                            .put(WorkflowContextKeys.LAST_EXITED_STATE, stateContext.getSource().getId());
                                    LOG.info("Action-{} = {}: {}", WorkflowBehaviorNames.RECORD_STATE_EXIT, WorkflowContextKeys.LAST_EXITED_STATE, stateContext.getSource().getId());
                                }
                        ),

                    WorkflowBehaviorNames.RECORD_TRANSITION,
                        Actions.from( stateContext -> {
                                    stateContext.getExtendedState()
                                            .getVariables()
                                            .put(WorkflowContextKeys.LAST_PROCESSED_EVENT, stateContext.getEvent());
                                    LOG.info("Action-{} = {}: {}", WorkflowBehaviorNames.RECORD_TRANSITION, WorkflowContextKeys.LAST_PROCESSED_EVENT, stateContext.getEvent());
                                }
                        ),

                    WorkflowBehaviorNames.OBSERVE_STATE,
                        Actions.from( stateContext -> LOG.debug("State activity for definition {}", stateContext.getStateMachine().getId()))
            );


    private final Map<String, Function<StateContext<String, String>, Mono<Boolean>>> guards =
            Map.of(WorkflowBehaviorNames.ALWAYS_ALLOW, stateContext -> Mono.just(true));



    public Function<StateContext<String, String>, Mono<Void>> resolveAction(String actionCode) {

        Function<StateContext<String, String>, Mono<Void>> action =
                actions.get(actionCode);

        if (action == null) {
            throw new IllegalStateException("Unregistered workflow action: " + actionCode);
        }

        return action;
    }

    public Function<StateContext<String, String>, Mono<Boolean>> resolveGuard(String guardCode) {

        Function<StateContext<String, String>, Mono<Boolean>> guard =
                guards.get(guardCode);

        if (guard == null) {
            throw new IllegalStateException("Unregistered workflow guard: " + guardCode);
        }
        return guard;
    }

}

