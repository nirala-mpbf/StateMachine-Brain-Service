package com.example.statemachine.repository;

import com.example.statemachine.config.GsonConfiguration;
import com.example.statemachine.dto.persistence.WorkflowInstance;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.statemachine.StateMachineContext;

import static org.junit.jupiter.api.Assertions.*;

class JdbcWorkflowStateMachinePersistenceTest {
    private final CapturingInstanceRepository workflowInstanceRepository = new CapturingInstanceRepository();
    private final JdbcWorkflowStateMachinePersistence stateMachinePersistence = new JdbcWorkflowStateMachinePersistence(workflowInstanceRepository, new GsonConfiguration().gson());

    @Test
    void restoresExistingJsonAndRoundTripsExtendedVariables() throws Exception {
        String contextJson = """
                {"formatVersion":1,"state":"PROFILE_DETAILS","variables":{
                  "completedChecks":["UPDATE_PROFILE"],"count":2,"large":2147483648,
                  "huge":9223372036854775808,"ratio":1.5,"enabled":true,
                  "optional":null,"label":"<review>&done","nested":{"value":3}}}
                """;
        StateMachineContext<String, String> context = stateMachinePersistence.deserializeContext(instance(contextJson, 1));
        var variables = context.getExtendedState().getVariables();
        assertEquals(List.of("UPDATE_PROFILE"), variables.get("completedChecks"));
        assertEquals(2, variables.get("count"));
        assertEquals(2147483648L, variables.get("large"));
        assertEquals(new BigInteger("9223372036854775808"), variables.get("huge"));
        assertEquals(1.5, variables.get("ratio"));
        assertEquals(true, variables.get("enabled"));
        assertTrue(variables.containsKey("optional"));
        assertNull(variables.get("optional"));
        stateMachinePersistence.write(context, "APP-101");
        assertEquals("APP-101", workflowInstanceRepository.reference);
        assertEquals("PROFILE_DETAILS", workflowInstanceRepository.state);
        assertEquals(variables, stateMachinePersistence.deserializeContext(instance(workflowInstanceRepository.contextJson, 1)).getExtendedState().getVariables());
    }

    @Test
    void restoresLegacyInstancesWithoutJson() throws Exception {
        StateMachineContext<String, String> context = stateMachinePersistence.deserializeContext(instance(null, 1));
        assertEquals("PROFILE_DETAILS", context.getState());
        assertTrue(context.getExtendedState().getVariables().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "{}", "{invalid}",
            "{\"formatVersion\":2,\"state\":\"PROFILE_DETAILS\",\"variables\":{}}",
            "{\"formatVersion\":1,\"state\":\"COMPLETED\",\"variables\":{}}",
            "{\"formatVersion\":1,\"state\":\"PROFILE_DETAILS\",\"variables\":null}",
            "{\"formatVersion\":1,\"state\":\"PROFILE_DETAILS\",\"variables\":{}} {}"
    })
    void rejectsInvalidOrInconsistentContexts(String contextJson) {
        assertThrows(Exception.class, () -> stateMachinePersistence.deserializeContext(instance(contextJson, 1)));
    }

    @Test
    void rejectsUnsupportedInstanceFormat() {
        assertThrows(IllegalStateException.class, () -> stateMachinePersistence.deserializeContext(instance(null, 2)));
    }

    private WorkflowInstance instance(String contextJson, int version) {
        return new WorkflowInstance(1, "APP-101", 1, "PROFILE_DETAILS", contextJson, version);
    }

    private static class CapturingInstanceRepository extends JdbcWorkflowInstanceRepository {
        private String reference;
        private String state;
        private String contextJson;

        CapturingInstanceRepository() {
            super(null);
        }

        @Override
        public void updateWorkflowContext(String reference, String state, String contextJson) {
            this.reference = reference;
            this.state = state;
            this.contextJson = contextJson;
        }
    }
}
