package com.example.statemachine.service;

import com.example.statemachine.api.WorkflowApi.AvailableTransition;
import com.example.statemachine.api.WorkflowApi.EventOutcome;
import com.example.statemachine.api.WorkflowApi.RequestMetadata;
import com.example.statemachine.api.WorkflowApi.WorkflowDetails;
import com.example.statemachine.definition.JdbcWorkflowDefinitionRepository;
import com.example.statemachine.definition.WorkflowDefinition;
import com.example.statemachine.exception.WorkflowFailure;
import com.example.statemachine.persistence.JdbcStateMachinePersist;
import com.example.statemachine.persistence.JdbcWorkflowHistoryRepository;
import com.example.statemachine.persistence.JdbcWorkflowInstanceRepository;
import com.example.statemachine.statemachine.WorkflowEventProcessor;
import com.example.statemachine.util.WorkflowContextKeys;

import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.persist.StateMachinePersister;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkflowExecutionService {
    private final JdbcWorkflowDefinitionRepository definitions;
    private final JdbcWorkflowInstanceRepository instances;
    private final StateMachineFactory<String, String> factory;
    private final StateMachinePersister<String, String, String> persister;
    private final JdbcStateMachinePersist contextStore;
    private final JdbcWorkflowHistoryRepository history;
    private final WorkflowEventProcessor eventProcessor;

    public WorkflowExecutionService(
            JdbcWorkflowDefinitionRepository definitions,
            JdbcWorkflowInstanceRepository instances,
            StateMachineFactory<String, String> factory,
            StateMachinePersister<String, String, String> persister,
            JdbcStateMachinePersist contextStore,
            JdbcWorkflowHistoryRepository history,
            WorkflowEventProcessor eventProcessor) {

        this.definitions = definitions;
        this.instances = instances;
        this.factory = factory;
        this.persister = persister;
        this.contextStore = contextStore;
        this.history = history;
        this.eventProcessor = eventProcessor;
    }


    @Transactional(rollbackFor = Exception.class)
    public WorkflowDetails createWorkflow(String referenceId, String workflowKey) throws Exception {
        String definitionId = definitions.findLatestActiveDefinitionId(workflowKey);
        var definition = definitions.loadWorkflowDefinition(definitionId);
        var machine = factory.getStateMachine(definitionId);
        try {
            machine.startReactively().block();
            eventProcessor.checkMachineError(machine);
            instances.create(referenceId, definitionId, machine.getState().getId());
            persister.persist(machine, referenceId);
            return buildWorkflowDetails(
                    referenceId,
                    definition,
                    machine.getState().getId(),
                    null,
                    pendingEvents(machine.getExtendedState().getVariables()));
        } finally {
            machine.stopReactively().block();
        }
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public WorkflowDetails getWorkflowState(String referenceId) throws Exception {
        // One row supplies state and context together. No lock, machine startup or actions on
        // reads.
        var instance = instances.findByReference(referenceId);
        var definition = definitions.loadWorkflowDefinition(instance.definitionId());
        var context = contextStore.readContext(instance);
        return buildWorkflowDetails(
                referenceId,
                definition,
                context.getState(),
                null,
                pendingEvents(context.getExtendedState().getVariables()));
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowDetails submitEvent(RequestMetadata metadata) throws Exception {

        JdbcWorkflowInstanceRepository.Instance instance = instances.lockByReference(metadata.referenceId());
        WorkflowDefinition definition = definitions.loadWorkflowDefinition(instance.definitionId());
        StateMachine<String, String> machine = factory.getStateMachine(instance.definitionId());

        try {
            persister.restore(machine, metadata.referenceId());
            eventProcessor.checkMachineError(machine);

            String event = metadata.event();

            if (definition.transitions().stream().noneMatch(transition -> transition.event().equals(event))) {
                throw WorkflowFailure.invalid("Unknown event for this workflow definition: " + event);
            }

            EventOutcome outcome;
            if (isDeferred(definition, machine.getState().getId(), event)) {
                var pending = new LinkedHashSet<>(
                        pendingEvents(machine.getExtendedState().getVariables()));
                pending.add(event);

                storePendingEvents(machine, pending);
                outcome = EventOutcome.DEFERRED;

            } else {
                processAndRecordEvent(machine, event, definition.id(), metadata);
                outcome = EventOutcome.APPLIED;
                processDeferredEvents(machine, definition, metadata);
            }

            if (definition.getState(machine.getState().getId()).terminal()) {
                storePendingEvents(machine, Set.of());
            }

            persister.persist(machine, metadata.referenceId());

            return buildWorkflowDetails(
                    metadata.referenceId(),
                    definition,
                    machine.getState().getId(),
                    outcome,
                    pendingEvents(machine.getExtendedState().getVariables()));
        } finally {
            machine.stopReactively().block();
        }
    }

    private void processAndRecordEvent(
            StateMachine<String, String> machine,
            String event,
            String definitionId,
            RequestMetadata metadata) {

        var result = eventProcessor.processEvent(machine, event);

        switch (result.outcome()) {
            case EVENT_NOT_ALLOWED -> throw WorkflowFailure.conflict(
                                    "EVENT_NOT_ALLOWED",
                                    event + " is not allowed while the workflow is " + result.currentState() + "."
                                    );

            case GUARD_REJECTED -> throw WorkflowFailure.conflict(
                            "GUARD_REJECTED",
                            "A workflow guard rejected " + event + ".");

            case APPLIED -> history.recordTransition(
                            metadata.referenceId(),
                            definitionId,
                            result.previousState(),
                            result.event(),
                            result.currentState(),
                            metadata.requestId(),
                            metadata.idempotencyKey());
        }
    }

    private void processDeferredEvents(StateMachine<String, String> machine, WorkflowDefinition definition, RequestMetadata metadata) {

        var waiting = new LinkedHashSet<>(pendingEvents(machine.getExtendedState().getVariables()));
        boolean progressed;

        do {
            progressed = false;
            for (String event : List.copyOf(waiting)) {
                String currentState = machine.getState().getId();
                if (definition.getState(currentState).terminal()) {
                    waiting.clear();
                    break;
                }
                boolean configured =
                        definition.transitions().stream()
                                .anyMatch(
                                        transition ->
                                                transition.source().equals(currentState)
                                                        && transition.event().equals(event));
                if (!isDeferred(definition, currentState, event) && configured) {
                    processAndRecordEvent(machine, event, definition.id(), metadata);
                    waiting.remove(event);
                    progressed = true;
                    break;
                }
            }
        } while (progressed && !waiting.isEmpty());
        storePendingEvents(machine, waiting);
    }

    private boolean isDeferred(WorkflowDefinition definition, String state, String event) {
        return definition.deferredEvents().stream()
                .anyMatch(
                        deferred ->
                                deferred.state().equals(state) && deferred.event().equals(event));
    }

    private List<String> pendingEvents(Map<Object, Object> variables) {
        Object value = variables.get(WorkflowContextKeys.PENDING_EVENTS);
        return value instanceof Collection<?> events
                ? events.stream().map(String.class::cast).toList()
                : List.of();
    }

    private void storePendingEvents(StateMachine<String, String> machine, Collection<String> events) {
        if (events.isEmpty()) {
            machine.getExtendedState().getVariables().remove(WorkflowContextKeys.PENDING_EVENTS);
        }
        else{
            machine.getExtendedState().getVariables().put(WorkflowContextKeys.PENDING_EVENTS, new ArrayList<>(events));
        }
    }

    private WorkflowDetails buildWorkflowDetails(
            String referenceId,
            WorkflowDefinition definition,
            String state,
            EventOutcome outcome,
            List<String> pendingEvents) {

        List<AvailableTransition> available =
                        definition.transitions()
                        .stream()
                        .filter(transition -> transition.source().equals(state))
                        .map( transition ->
                                new AvailableTransition(transition.event(), transition.target())
                        )
                        .toList();

        AvailableTransition next = available.size() == 1 ? available.getFirst() : null;

        return new WorkflowDetails(
                referenceId,
                definition.workflowKey(),
                definition.version(),
                state,
                next == null ? null : next.event(),
                next == null ? null : next.nextState(),
                definition.getState(state).terminal(),
                available,
                outcome,
                pendingEvents
        );
    }
}
