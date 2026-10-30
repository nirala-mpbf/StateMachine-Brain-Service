package com.example.statemachine.persistence;

import org.springframework.statemachine.StateMachineContext;
import org.springframework.statemachine.StateMachinePersist;
import org.springframework.statemachine.support.DefaultExtendedState;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/** Versioned JSON context for this application's flat, single-region machines. */

@Component
public class JdbcStateMachinePersist implements StateMachinePersist<String, String, String> {

    public record StoredContext(
            int formatVersion,
            String state,
            Map<String, Object> variables
    ) {}

    private final JdbcWorkflowInstanceRepository instanceRepository;
    private final ObjectMapper objectMapper;

    public JdbcStateMachinePersist(JdbcWorkflowInstanceRepository instanceRepository, ObjectMapper objectMapper) {
        this.instanceRepository = instanceRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void write(StateMachineContext<String, String> context, String reference) throws Exception {

        if (context.getChilds() != null && !context.getChilds().isEmpty())
            throw new IllegalStateException(
                    "Nested/parallel machine contexts are not supported by context format 1.");

        Map<String, Object> variables = new HashMap<>();
        if (context.getExtendedState() != null) {
            context.getExtendedState()
                    .getVariables()
                    .forEach( (key, value) -> {
                                if (!(key instanceof String)) {
                                    throw new IllegalStateException("Extended-state keys must be strings.");
                                }
                                variables.put((String) key, value);
                            }
                    );
        }
        instanceRepository.saveContext(
                reference,
                context.getState(),
                objectMapper.writeValueAsString(new StoredContext(1, context.getState(), variables))
        );
    }

    @Override
    public StateMachineContext<String, String> read(String reference) throws Exception {
        return readContext(instanceRepository.findByReference(reference));
    }

    public StateMachineContext<String, String> readContext(
            JdbcWorkflowInstanceRepository.Instance instance) throws Exception {

        if (instance.formatVersion() != 1) {
            throw new IllegalStateException("Unsupported context format.");
        }

        StoredContext saved = instance.context() == null
                        ? new StoredContext(1, instance.state(), Map.of())
                        : objectMapper.readValue(instance.context(), StoredContext.class);

        if (saved.formatVersion() != 1 || !instance.state().equals(saved.state()))
            throw new IllegalStateException("Persisted context does not match current_state.");

        Map<Object, Object> variables = new HashMap<>(saved.variables());
        return new DefaultStateMachineContext<>(
                saved.state(), null, null, new DefaultExtendedState(variables));
    }
}
