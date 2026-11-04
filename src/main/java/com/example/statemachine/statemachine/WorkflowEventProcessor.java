package com.example.statemachine.statemachine;

import com.example.statemachine.dto.workflow.WorkflowEventProcessingResult;
import com.example.statemachine.dto.workflow.WorkflowStepContext;
import com.example.statemachine.constants.WorkflowConstants;
import com.example.statemachine.enums.WorkflowEventProcessingOutcome;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.StateMachineEventResult;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.transition.Transition;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Sends one event to Spring StateMachine; HTTP policy and persistence belong to the service.
 */

@Component
public class WorkflowEventProcessor {

    public WorkflowEventProcessingResult processEvent(StateMachine<String, String> stateMachine, String event) {
        return processEvent(stateMachine, event, null);
    }

    public WorkflowEventProcessingResult processEvent(
            StateMachine<String, String> stateMachine, String event, WorkflowStepContext stepContext) {
        String previousStateCode = stateMachine.getState().getId();
        AtomicBoolean transitionApplied = new AtomicBoolean();

        StateMachineListenerAdapter<String, String> transitionListener = new StateMachineListenerAdapter<String, String>() {
            @Override
            public void transition(Transition<String, String> transition) {
                if (transition.getTrigger() != null && event.equals(transition.getTrigger().getEvent()))
                    transitionApplied.set(true);
            }
        };

        stateMachine.addStateListener(transitionListener);

        try {

            List<StateMachineEventResult<String, String>> eventResults =
                    stateMachine.sendEvent(
                        Mono.just(
                            MessageBuilder
                            .withPayload(event)
                            .setHeader(WorkflowConstants.WORKFLOW_STEP_CONTEXT_HEADER, stepContext)
                            .build()
                        )
                    )
                    .collectList()
                    .block();

            throwIfStateMachineFailed(stateMachine);

            if (eventResults == null || eventResults.isEmpty() || eventResults.stream().anyMatch(eventResult -> eventResult.getResultType() == StateMachineEventResult.ResultType.DENIED)) {
                return new WorkflowEventProcessingResult(
                        previousStateCode,
                        event,
                        stateMachine.getState().getId(),
                        WorkflowEventProcessingOutcome.EVENT_NOT_ALLOWED
                );
            }

            eventResults.forEach( eventResult -> eventResult   .complete().block() );

            throwIfStateMachineFailed(stateMachine);

            return new WorkflowEventProcessingResult(
                    previousStateCode,
                    event,
                    stateMachine.getState().getId(),
                    transitionApplied.get() ? WorkflowEventProcessingOutcome.APPLIED : WorkflowEventProcessingOutcome.EVENT_NOT_ALLOWED
            );

        } finally {
            stateMachine.removeStateListener(transitionListener);
        }
    }

    public void throwIfStateMachineFailed(StateMachine<String, String> stateMachine) {
        if (stateMachine.hasStateMachineError()) {
            throw new IllegalStateException("State-machine action failed.");
        }
    }
}
