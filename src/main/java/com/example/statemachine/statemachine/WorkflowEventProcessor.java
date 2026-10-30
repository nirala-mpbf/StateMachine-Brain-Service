package com.example.statemachine.statemachine;

import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.StateMachineEventResult;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.transition.Transition;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends one event to Spring StateMachine; HTTP policy and persistence belong to the service.
 */

@Component
public class WorkflowEventProcessor {

    public enum Outcome {
        APPLIED, EVENT_NOT_ALLOWED, GUARD_REJECTED
    }

    public record EventProcessingResult(
            String previousState,
            String event,
            String currentState,
            Outcome outcome
    ) { }

    public EventProcessingResult processEvent(StateMachine<String, String> machine, String event) {
        String previousState = machine.getState().getId();
        var transitioned = new AtomicBoolean();

        var listener = new StateMachineListenerAdapter<String, String>() {
            @Override
            public void transition(Transition<String, String> transition) {
                if (transition.getTrigger() != null && event.equals(transition.getTrigger().getEvent()))
                    transitioned.set(true);
            }
        };

        machine.addStateListener(listener);

        try {

            List<StateMachineEventResult<String, String>> results =
                    machine.sendEvent(
                        Mono.just(
                            MessageBuilder
                            .withPayload(event)
                            .build()
                        )
                    )
                    .collectList()
                    .block();

            checkMachineError(machine);

            if (results == null || results.isEmpty() || results.stream().anyMatch(result -> result.getResultType() == StateMachineEventResult.ResultType.DENIED)) {
                return new EventProcessingResult(
                        previousState,
                        event,
                        machine.getState().getId(),
                        Outcome.EVENT_NOT_ALLOWED
                );
            }

            results.forEach( result -> result   .complete().block() );

            checkMachineError(machine);

            return new EventProcessingResult(
                    previousState,
                    event,
                    machine.getState().getId(),
                    transitioned.get() ? Outcome.APPLIED : Outcome.GUARD_REJECTED
            );

        } finally {
            machine.removeStateListener(listener);
        }
    }

    public void checkMachineError(StateMachine<String, String> machine) {
        if (machine.hasStateMachineError()) {
            throw new IllegalStateException("State-machine action failed.");
        }
    }
}
