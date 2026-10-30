package com.example.statemachine.statemachine;

import com.example.statemachine.definition.JdbcWorkflowDefinitionRepository;
import com.example.statemachine.definition.WorkflowDefinition;

import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.config.model.DefaultStateMachineModel;
import org.springframework.statemachine.config.model.StateData;
import org.springframework.statemachine.config.model.StateMachineModel;
import org.springframework.statemachine.config.model.StateMachineModelFactory;
import org.springframework.statemachine.config.model.StatesData;
import org.springframework.statemachine.config.model.TransitionData;
import org.springframework.statemachine.config.model.TransitionsData;
import org.springframework.statemachine.transition.TransitionKind;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.function.Function;

@Component
public class JdbcWorkflowModelFactory implements StateMachineModelFactory<String, String> {

    private final JdbcWorkflowDefinitionRepository definitions;
    private final WorkflowBehaviorRegistry behaviors;

    public JdbcWorkflowModelFactory(
            JdbcWorkflowDefinitionRepository definitions, WorkflowBehaviorRegistry behaviors) {
        this.definitions = definitions;
        this.behaviors = behaviors;
    }

    @Override
    public StateMachineModel<String, String> build() {
        throw new IllegalStateException("A versioned definition ID is required.");
    }

    @Override
    public StateMachineModel<String, String> build(String definitionId) {
        WorkflowDefinition definition = definitions.loadWorkflowDefinition(definitionId);
        validateWorkflowStructure(definition);

        List<StateData<String, String>> states =
                definition.states().stream()
                .map( stateDefinition -> {

                            StateData<String, String> state =
                                new StateData<String, String>(stateDefinition.code(), stateDefinition.initial());

                            state.setEnd(stateDefinition.terminal());
                            state.setEntryActions(resolveStateActions(stateDefinition, "ENTRY"));
                            state.setExitActions(resolveStateActions(stateDefinition, "EXIT"));
                            state.setStateActions(resolveStateActions(stateDefinition, "STATE"));
                            state.setDeferred(
                                    definition.deferredEvents().stream()
                                            .filter(d -> d.state().equals(stateDefinition.code()))
                                            .map(deferredEvent -> deferredEvent.event())
                                            .toList()
                            );
                            return state;
                        }
                )
                .toList();

        List<TransitionData<String, String>> transitions =
                definition.transitions().stream()
                .map( transition -> new TransitionData<String, String>(
                        transition.source(),
                        transition.target(),
                        transition.event(),
                        resolveTransitionActions(transition.actions()),
                        transition.guard() == null ? null : resolveGuardWithErrorHandling(transition.guard()),
                        TransitionKind.valueOf(transition.kind())
                    )
                )
                .toList();

        return new DefaultStateMachineModel<>(
                null,
                new StatesData<>(states),
                new TransitionsData<>(transitions)
        );
    }

    private Collection<Function<StateContext<String, String>, Mono<Void>>> resolveStateActions(
            WorkflowDefinition.State state, String phase) {

        List<Function<StateContext<String, String>, Mono<Void>>> orderedAction =
                state.actions().stream()
                .filter(action -> action.phase().equals(phase))
                .sorted(Comparator.comparingInt(WorkflowDefinition.ActionBinding::order))
                .map(action -> resolveActionWithErrorHandling(action.code()))
                .toList();

        return orderedAction.isEmpty()
                ? List.of()
                : List.of( context ->
                            Flux.fromIterable(orderedAction)
                            .concatMap(action -> action.apply(context))
                            .then()
                );
    }

    private Collection<Function<StateContext<String, String>, Mono<Void>>> resolveTransitionActions(
            List<String> codes) {

        List<Function<StateContext<String, String>, Mono<Void>>> ordered =
                codes.stream()
                .map(code -> resolveActionWithErrorHandling(code))
                .toList();

        return ordered.isEmpty()
                ? List.of()
                : List.of( context ->
                        Flux.fromIterable(ordered)
                        .concatMap(action -> action.apply(context))
                        .then()
                );
    }

    private Function<StateContext<String, String>, Mono<Void>> resolveActionWithErrorHandling(String code) {

        var action = behaviors.resolveAction(code);
        return context -> Mono.defer(() -> action.apply(context))
                .doOnError(
                        error ->
                        context.getStateMachine()
                        .setStateMachineError(new IllegalStateException("Workflow action failed: " + code, error))
                );
    }

    private Function<StateContext<String, String>, Mono<Boolean>> resolveGuardWithErrorHandling(String code) {
        var guard = behaviors.resolveGuard(code);
        return context -> Mono.defer(() -> guard.apply(context))
                .doOnError( error ->
                        context.getStateMachine()
                        .setStateMachineError(new IllegalStateException("Workflow guard failed: " + code, error))
                );
    }

    private void validateWorkflowStructure(WorkflowDefinition definition) {

        if (definition.states().stream().filter(WorkflowDefinition.State::initial).count() != 1) {
            throw new IllegalStateException("Workflow must have exactly one initial state.");
        }

        if (definition.states().stream().noneMatch(WorkflowDefinition.State::terminal)) {
            throw new IllegalStateException("Workflow must have a terminal state.");
        }

        for (var transition : definition.transitions()) {

            if (definition.getState(transition.source()).terminal()) {
                throw new IllegalStateException("Terminal states cannot have outgoing transitions.");
            }
            definition.getState(transition.target());
        }

        Set<String> reachable = new HashSet<String>();
        reachable.add(
                definition.states()
                .stream()
                .filter(WorkflowDefinition.State::initial)
                .findFirst()
                .orElseThrow()
                .code()
        );

        boolean changed;
        do {
            changed = false;
            for (var t : definition.transitions()) {
                if (reachable.contains(t.source())){
                    changed |= reachable.add(t.target());
                }
            }
        } while (changed);

        if (reachable.size() != definition.states().size())
            throw new IllegalStateException("Workflow contains unreachable states.");
        for (var deferred : definition.deferredEvents()) {
            if (definition.getState(deferred.state()).terminal())
                throw new IllegalStateException("Terminal states cannot defer events.");
            if (definition.transitions().stream()
                    .noneMatch(t -> t.event().equals(deferred.event())))
                throw new IllegalStateException(
                        "Deferred event has no transition handler: " + deferred.event());
        }
    }
}
