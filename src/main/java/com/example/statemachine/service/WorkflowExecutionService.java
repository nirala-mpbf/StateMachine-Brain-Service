package com.example.statemachine.service;

import lombok.AllArgsConstructor;
import com.example.statemachine.constants.ApiErrorConstants;
import com.example.statemachine.dto.api.AvailableWorkflowTransition;
import com.example.statemachine.dto.api.WorkflowCommandMetadata;
import com.example.statemachine.dto.api.WorkflowDetails;
import com.example.statemachine.dto.persistence.WorkflowInstance;
import com.example.statemachine.dto.workflow.WorkflowDefinition;
import com.example.statemachine.dto.workflow.WorkflowStepContext;
import com.example.statemachine.dto.workflow.WorkflowEventProcessingResult;
import com.example.statemachine.enums.LogValueSanitizerFunction;
import com.example.statemachine.enums.WorkflowEventOutcome;
import com.example.statemachine.exception.WorkflowException;
import com.example.statemachine.repository.JdbcWorkflowDefinitionRepository;
import com.example.statemachine.repository.JdbcWorkflowHistoryRepository;
import com.example.statemachine.repository.JdbcWorkflowInstanceRepository;
import com.example.statemachine.repository.JdbcWorkflowStateMachinePersistence;
import com.example.statemachine.statemachine.WorkflowEventProcessor;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.StateMachineContext;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.persist.StateMachinePersister;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@AllArgsConstructor
public class WorkflowExecutionService {

    private final JdbcWorkflowDefinitionRepository workflowDefinitionRepository;
    private final JdbcWorkflowInstanceRepository workflowInstanceRepository;
    private final StateMachineFactory<String, String> stateMachineFactory;
    private final StateMachinePersister<String, String, String> stateMachinePersister;
    private final JdbcWorkflowStateMachinePersistence workflowStateMachinePersistence;
    private final JdbcWorkflowHistoryRepository workflowHistoryRepository;
    private final WorkflowEventProcessor workflowEventProcessor;

    @Transactional(rollbackFor = Exception.class)
    public WorkflowDetails createWorkflow(String referenceId, String workflowKey) throws Exception {

        Integer definitionId = workflowDefinitionRepository.findLatestActiveDefinitionId(workflowKey);
        WorkflowDefinition workflowDefinition = workflowDefinitionRepository.loadWorkflowDefinition(definitionId);

        StateMachine<String, String> stateMachine = stateMachineFactory.getStateMachine(definitionId.toString());
        try {

            stateMachine.startReactively().block();
            workflowEventProcessor.throwIfStateMachineFailed(stateMachine);

            workflowInstanceRepository.createWorkflowInstance(referenceId, definitionId, stateMachine.getState().getId());
            stateMachinePersister.persist(stateMachine, referenceId);
            log.debug("workflow_created definition={} state={}",
                    definitionId, LogValueSanitizerFunction.SANITIZE.apply(stateMachine.getState().getId()));
            return buildWorkflowDetails(
                    referenceId,
                    workflowDefinition,
                    stateMachine.getState().getId(),
                    null);
        } finally {
            stateMachine.stopReactively().block();
        }
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public WorkflowDetails getWorkflowState(String referenceId) throws Exception {

        WorkflowInstance workflowInstance = workflowInstanceRepository.findByReferenceId(referenceId);
        WorkflowDefinition workflowDefinition = workflowDefinitionRepository.loadWorkflowDefinition(workflowInstance.definitionId());

        StateMachineContext<String, String> stateMachineContext = workflowStateMachinePersistence.deserializeContext(workflowInstance);
        log.debug("workflow_state_loaded definition={} state={}",
                workflowInstance.definitionId(), LogValueSanitizerFunction.SANITIZE.apply(stateMachineContext.getState()));
        return buildWorkflowDetails(
                referenceId,
                workflowDefinition,
                stateMachineContext.getState(),
                null);
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowDetails submitEvent(WorkflowCommandMetadata commandMetadata) throws Exception {

        WorkflowInstance workflowInstance = workflowInstanceRepository.findAndLockByReferenceId(commandMetadata.referenceId());
        WorkflowDefinition workflowDefinition = workflowDefinitionRepository.loadWorkflowDefinition(workflowInstance.definitionId());

        StateMachine<String, String> stateMachine = stateMachineFactory.getStateMachine(workflowInstance.definitionId().toString());

        try {
            stateMachinePersister.restore(stateMachine, commandMetadata.referenceId());
            workflowEventProcessor.throwIfStateMachineFailed(stateMachine);

            String eventCode = commandMetadata.event();

            if (workflowDefinition.transitions().stream().noneMatch(transition -> transition.event().equals(eventCode))) {
                throw WorkflowException.invalid("Unknown event for this workflow definition: " + eventCode);
            }

            processAndRecordEvent(stateMachine, eventCode, workflowDefinition, commandMetadata);

            stateMachinePersister.persist(stateMachine, commandMetadata.referenceId());

            return buildWorkflowDetails(
                    commandMetadata.referenceId(),
                    workflowDefinition,
                    stateMachine.getState().getId(),
                    WorkflowEventOutcome.APPLIED);
        } finally {
            stateMachine.stopReactively().block();
        }
    }

    private void processAndRecordEvent(
            StateMachine<String, String> stateMachine,
            String eventCode,
            WorkflowDefinition workflowDefinition,
            WorkflowCommandMetadata commandMetadata) {

        WorkflowEventProcessingResult eventResult = workflowEventProcessor.processEvent(stateMachine, eventCode,
                new WorkflowStepContext(commandMetadata, workflowDefinition.id(),
                        workflowDefinition.workflowKey(), workflowDefinition.version()));

        log.debug("workflow_event_processed event={} sourceState={} targetState={} outcome={}",
                LogValueSanitizerFunction.SANITIZE.apply(eventCode), LogValueSanitizerFunction.SANITIZE.apply(eventResult.previousState()), LogValueSanitizerFunction.SANITIZE.apply(eventResult.currentState()), eventResult.outcome());

        switch (eventResult.outcome()) {
            case EVENT_NOT_ALLOWED -> throw WorkflowException.conflict(
                                    ApiErrorConstants.EVENT_NOT_ALLOWED,
                                    eventCode + " is not allowed while the workflow is " + eventResult.currentState() + "."
                                    );

            case APPLIED -> workflowHistoryRepository.recordTransition(
                            commandMetadata.referenceId(),
                            workflowDefinition.id(),
                            eventResult.previousState(),
                            eventResult.event(),
                            eventResult.currentState(),
                            commandMetadata.requestId(),
                            commandMetadata.idempotencyKey());
        }
    }

    private WorkflowDetails buildWorkflowDetails(
            String referenceId,
            WorkflowDefinition workflowDefinition,
            String stateCode,
            WorkflowEventOutcome eventOutcome) {

        List<AvailableWorkflowTransition> availableTransitions =
                        workflowDefinition.transitions()
                        .stream()
                        .filter(transition -> transition.source().equals(stateCode))
                        .map( transition ->
                                new AvailableWorkflowTransition(transition.event(), transition.target())
                        )
                        .toList();

        AvailableWorkflowTransition nextTransition = availableTransitions.size() == 1 ? availableTransitions.getFirst() : null;

        return new WorkflowDetails(
                referenceId,
                workflowDefinition.workflowKey(),
                workflowDefinition.version(),
                stateCode,
                nextTransition == null ? null : nextTransition.event(),
                nextTransition == null ? null : nextTransition.nextState(),
                workflowDefinition.getState(stateCode).terminal(),
                availableTransitions,
                eventOutcome
        );
    }
}
