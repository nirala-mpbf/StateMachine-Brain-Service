package com.example.statemachine.definition;

import java.util.List;

public record WorkflowDefinition(
        String id,
        String workflowKey,
        int version,
        List<State> states,
        List<Transition> transitions,
        List<DeferredEvent> deferredEvents
) {

    public record State(
            String code,
            boolean initial,
            boolean terminal,
            List<ActionBinding> actions
    ) {}

    public record ActionBinding(
            String code,
            String phase,
            int order
    ) {}

    public record Transition(
            String id,
            String source,
            String event,
            String target,
            String kind,
            String guard,
            List<String> actions
    ) {}

    public record DeferredEvent(
            String state,
            String event
    ) {}

    public State getState(String stateCode) {
        return states.stream()
                .filter(s -> s.code().equals(stateCode))
                .findFirst()
                .orElseThrow(
                        () ->  new IllegalStateException("State is missing from the workflow definition: " + stateCode)
                );
    }
}
