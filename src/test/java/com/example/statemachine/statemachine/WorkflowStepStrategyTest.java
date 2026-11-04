package com.example.statemachine.statemachine;

import com.example.statemachine.config.GsonConfiguration;
import com.example.statemachine.dto.api.WorkflowCommandMetadata;
import com.example.statemachine.dto.workflow.WorkflowDefinition;
import com.example.statemachine.dto.workflow.WorkflowStateDefinition;
import com.example.statemachine.dto.workflow.WorkflowStepContext;
import com.example.statemachine.dto.workflow.WorkflowTransitionDefinition;
import com.example.statemachine.enums.WorkflowEventProcessingOutcome;
import com.example.statemachine.repository.JdbcWorkflowDefinitionRepository;
import com.example.statemachine.repository.JdbcWorkflowStateMachinePersistence;
import com.example.statemachine.strategy.WorkflowStepStrategy;
import com.example.statemachine.strategy.step.UpdateProfileStepStrategy;
import com.example.statemachine.strategy.step.UploadAccountStatementStepStrategy;
import com.example.statemachine.strategy.step.UploadUdyamDocumentStepStrategy;
import com.example.statemachine.strategy.step.VerifyOtpStepStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowStepStrategyTest {
    @Test
    void discoversAndBindsEveryBuiltInStepImplementation() {
        try (var context = new AnnotationConfigApplicationContext(WorkflowBehaviorRegistry.class,
                VerifyOtpStepStrategy.class, UpdateProfileStepStrategy.class,
                UploadAccountStatementStepStrategy.class, UploadUdyamDocumentStepStrategy.class)) {
            var registry = context.getBean(WorkflowBehaviorRegistry.class);
            for (String action : List.of("verifyOtp", "updateProfile", "uploadAccountStatement", "uploadUdyamDocument")) {
                assertNotNull(registry.resolveActionByName(action));
            }
            assertThrows(IllegalStateException.class, () -> registry.resolveActionByName("missingImplementation"));
        }
    }

    @Test
    void databaseOrderAndNewStrategiesWorkAcrossMultipleWorkflows() {
        List<String> executions = new ArrayList<>();
        WorkflowStepStrategy verifyOtp = strategy("verifyOtp", command -> {
            assertNotNull(command.metadata().idempotencyKey());
            executions.add(command.workflowKey() + ":otp");
        });
        WorkflowStepStrategy updateProfile = strategy("updateProfile",
                command -> executions.add(command.workflowKey() + ":profile"));
        WorkflowStepStrategy additionalStep = strategy("additionalCheck",
                command -> executions.add(command.workflowKey() + ":extra"));
        var onboarding = definition(1, "onboarding", List.of("verifyOtp", "updateProfile"));
        var reordered = definition(2, "other-workflow", List.of("updateProfile", "additionalCheck", "verifyOtp"));
        try (var context = applicationContext(Map.of(1, onboarding, 2, reordered),
                verifyOtp, updateProfile, additionalStep)) {
            runWorkflow(context, onboarding);
            runWorkflow(context, reordered);
        }
        assertEquals(List.of("onboarding:otp", "onboarding:profile", "other-workflow:profile",
                "other-workflow:extra", "other-workflow:otp"), executions);
    }

    @Test
    void rejectedEventsDoNotRunStrategiesAndActionFailuresReachTheCaller() {
        List<String> executions = new ArrayList<>();
        var definition = definition(1, "failure-test", List.of("failingStep", "laterStep"));
        var failing = strategy("failingStep", command -> {
            executions.add("failed");
            throw new Exception("External service rejected the request");
        });
        var later = strategy("laterStep", command -> executions.add("later"));
        try (var context = applicationContext(Map.of(1, definition), failing, later)) {
            var machine = machine(context, definition);
            try {
                machine.startReactively().block();
                assertTrue(executions.isEmpty(), "Starting a workflow must not execute a business step");
                var processor = new WorkflowEventProcessor();
                assertEquals(WorkflowEventProcessingOutcome.EVENT_NOT_ALLOWED,
                        processor.processEvent(machine, "EVENT_1", stepContext(definition, "EVENT_1")).outcome());
                assertTrue(executions.isEmpty());
                assertThrows(IllegalStateException.class,
                        () -> processor.processEvent(machine, "EVENT_0", stepContext(definition, "EVENT_0")));
                assertEquals(List.of("failed"), executions);
                assertTrue(machine.hasStateMachineError());
            } finally {
                machine.stopReactively().block();
            }
        }
    }

    @Test
    void missingCommandContextFailsRatherThanRunningABusinessStep() {
        List<String> executions = new ArrayList<>();
        var definition = definition(1, "context-test", List.of("customStep"));
        try (var context = applicationContext(Map.of(1, definition),
                strategy("customStep", command -> executions.add("executed")))) {
            var machine = machine(context, definition);
            try {
                machine.startReactively().block();
                assertThrows(IllegalStateException.class,
                        () -> new WorkflowEventProcessor().processEvent(machine, "EVENT_0"));
                assertTrue(executions.isEmpty());
            } finally {
                machine.stopReactively().block();
            }
        }
    }

    @Test
    void duplicateOrReservedStrategyNamesFailAtRegistration() {
        var first = strategy("duplicate", command -> { });
        var second = strategy("duplicate", command -> { });
        var duplicateRegistry = new WorkflowBehaviorRegistry(List.of(first, second));
        assertThrows(IllegalStateException.class, duplicateRegistry::registerStepStrategies);
        var reservedRegistry = new WorkflowBehaviorRegistry(List.of(strategy("recordTransition", command -> { })));
        assertThrows(IllegalStateException.class, reservedRegistry::registerStepStrategies);
        var blankRegistry = new WorkflowBehaviorRegistry(List.of(strategy(" ", command -> { })));
        assertThrows(IllegalStateException.class, blankRegistry::registerStepStrategies);
    }

    private void runWorkflow(AnnotationConfigApplicationContext context, WorkflowDefinition definition) {
        var machine = machine(context, definition);
        try {
            machine.startReactively().block();
            for (var transition : definition.transitions()) {
                assertEquals(WorkflowEventProcessingOutcome.APPLIED,
                        new WorkflowEventProcessor().processEvent(machine, transition.event(),
                                stepContext(definition, transition.event())).outcome());
                assertEquals(transition.target(), machine.getState().getId());
            }
            assertTrue(machine.isComplete());
        } finally {
            machine.stopReactively().block();
        }
    }

    @SuppressWarnings("unchecked")
    private StateMachine<String, String> machine(AnnotationConfigApplicationContext context, WorkflowDefinition definition) {
        return context.getBean(StateMachineFactory.class).getStateMachine(definition.id().toString());
    }

    private WorkflowStepContext stepContext(WorkflowDefinition definition, String event) {
        return new WorkflowStepContext(new WorkflowCommandMetadata("SUBMIT_EVENT", "request-1", "APP-101",
                event, "key-" + event, null), definition.id(), definition.workflowKey(), definition.version());
    }

    private WorkflowDefinition definition(int id, String key, List<String> orderedActions) {
        List<WorkflowStateDefinition> states = new ArrayList<>();
        List<WorkflowTransitionDefinition> transitions = new ArrayList<>();
        for (int index = 0; index <= orderedActions.size(); index++) {
            states.add(new WorkflowStateDefinition("STATE_" + index, index == 0,
                    index == orderedActions.size(), List.of()));
            if (index < orderedActions.size()) {
                transitions.add(new WorkflowTransitionDefinition(index + 1, "STATE_" + index, "EVENT_" + index,
                        "STATE_" + (index + 1), "EXTERNAL", List.of(orderedActions.get(index), "recordTransition")));
            }
        }
        return new WorkflowDefinition(id, key, 1, states, transitions);
    }

    private AnnotationConfigApplicationContext applicationContext(
            Map<Integer, WorkflowDefinition> definitions, WorkflowStepStrategy... strategies) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(JdbcWorkflowDefinitionRepository.class, () -> new JdbcWorkflowDefinitionRepository(null) {
            @Override
            public WorkflowDefinition loadWorkflowDefinition(Integer id) { return definitions.get(id); }
        });
        for (int index = 0; index < strategies.length; index++) {
            var strategy = strategies[index];
            context.registerBean("strategy" + index, WorkflowStepStrategy.class, () -> strategy);
        }
        context.register(WorkflowBehaviorRegistry.class, JdbcWorkflowModelFactory.class, WorkflowEngineConfiguration.class);
        context.registerBean(JdbcWorkflowStateMachinePersistence.class,
                () -> new JdbcWorkflowStateMachinePersistence(null, new GsonConfiguration().gson()));
        context.refresh();
        return context;
    }

    private WorkflowStepStrategy strategy(String name, StepExecution execution) {
        return new WorkflowStepStrategy() {
            @Override public String actionName() { return name; }
            @Override public void execute(WorkflowStepContext context) throws Exception { execution.execute(context); }
        };
    }

    @FunctionalInterface
    private interface StepExecution {
        void execute(WorkflowStepContext context) throws Exception;
    }
}
