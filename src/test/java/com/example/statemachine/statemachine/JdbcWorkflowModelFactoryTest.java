package com.example.statemachine.statemachine;

import com.example.statemachine.config.GsonConfiguration;
import com.example.statemachine.dto.workflow.WorkflowActionBinding;
import com.example.statemachine.dto.workflow.WorkflowDefinition;
import com.example.statemachine.dto.workflow.WorkflowStateDefinition;
import com.example.statemachine.dto.workflow.WorkflowTransitionDefinition;
import com.example.statemachine.enums.WorkflowEventProcessingOutcome;
import com.example.statemachine.repository.JdbcWorkflowDefinitionRepository;
import com.example.statemachine.repository.JdbcWorkflowStateMachinePersistence;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcWorkflowModelFactoryTest {
    @Test
    void convertsFrameworkMachineIdentifierToIntegerDefinitionId() {
        CapturingDefinitionRepository definitionRepository = new CapturingDefinitionRepository();
        JdbcWorkflowModelFactory modelFactory = new JdbcWorkflowModelFactory(
                definitionRepository, new WorkflowBehaviorRegistry(List.of()));

        assertNotNull(modelFactory.build("7"));
        assertEquals(Integer.valueOf(7), definitionRepository.requestedDefinitionId);
        assertEquals(Integer.valueOf(19), definitionRepository.definition.transitions().getFirst().id());
        WorkflowActionBinding actionBinding = definitionRepository.definition.states().getFirst().actions().getFirst();
        assertEquals(Integer.valueOf(101), actionBinding.actionId());
        assertEquals("recordStateEntry", actionBinding.actionName());
    }

    @Test
    void rejectsNonNumericFrameworkIdentifiers() {
        JdbcWorkflowModelFactory modelFactory = new JdbcWorkflowModelFactory(
                new CapturingDefinitionRepository(), new WorkflowBehaviorRegistry(List.of()));
        assertThrows(NumberFormatException.class, () -> modelFactory.build("legacy-definition-key"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesEventsAndRejectsInvalidEventsWithoutConditionalProcessing() {
        try (AnnotationConfigApplicationContext context = applicationContext(new CapturingDefinitionRepository())) {
            StateMachineFactory<String, String> factory = context.getBean(StateMachineFactory.class);
            StateMachine<String, String> machine = factory.getStateMachine("7");
            WorkflowEventProcessor processor = new WorkflowEventProcessor();
            try {
                machine.startReactively().block();
                assertEquals(WorkflowEventProcessingOutcome.EVENT_NOT_ALLOWED, processor.processEvent(machine, "UNKNOWN").outcome());
                assertEquals(WorkflowEventProcessingOutcome.APPLIED, processor.processEvent(machine, "FINISH").outcome());
                assertEquals("END", machine.getState().getId());
                assertEquals(WorkflowEventProcessingOutcome.EVENT_NOT_ALLOWED, processor.processEvent(machine, "FINISH").outcome());
            } finally {
                machine.stopReactively().block();
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void recognizesInternalTransitionsThatKeepTheSameState() {
        CapturingDefinitionRepository repository = new CapturingDefinitionRepository();
        repository.definition = new WorkflowDefinition(7, "onboarding", 1, repository.definition.states(), List.of(
                new WorkflowTransitionDefinition(18, "START", "NOTE", "START", "INTERNAL", List.of("recordTransition")),
                repository.definition.transitions().getFirst()));
        try (AnnotationConfigApplicationContext context = applicationContext(repository)) {
            StateMachineFactory<String, String> factory = context.getBean(StateMachineFactory.class);
            StateMachine<String, String> machine = factory.getStateMachine("7");
            try {
                machine.startReactively().block();
                assertEquals(WorkflowEventProcessingOutcome.APPLIED, new WorkflowEventProcessor().processEvent(machine, "NOTE").outcome());
                assertEquals("START", machine.getState().getId());
            } finally {
                machine.stopReactively().block();
            }
        }
    }

    private AnnotationConfigApplicationContext applicationContext(CapturingDefinitionRepository repository) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(JdbcWorkflowDefinitionRepository.class, () -> repository);
        context.registerBean(WorkflowBehaviorRegistry.class);
        context.registerBean(JdbcWorkflowModelFactory.class);
        context.registerBean(JdbcWorkflowStateMachinePersistence.class,
                () -> new JdbcWorkflowStateMachinePersistence(null, new GsonConfiguration().gson()));
        context.register(WorkflowEngineConfiguration.class);
        context.refresh();
        return context;
    }

    private static class CapturingDefinitionRepository extends JdbcWorkflowDefinitionRepository {
        private Integer requestedDefinitionId;
        private WorkflowDefinition definition = new WorkflowDefinition(
                7, "onboarding", 1,
                List.of(new WorkflowStateDefinition("START", true, false,
                        List.of(new WorkflowActionBinding(101, "recordStateEntry", "ENTRY", 0))),
                        new WorkflowStateDefinition("END", false, true, List.of())),
                List.of(new WorkflowTransitionDefinition(19, "START", "FINISH", "END", "EXTERNAL", List.of("recordTransition"))));

        CapturingDefinitionRepository() {
            super(null);
        }

        @Override
        public WorkflowDefinition loadWorkflowDefinition(Integer definitionId) {
            requestedDefinitionId = definitionId;
            return definition;
        }
    }
}
