package com.example.statemachine.statemachine;

import com.example.statemachine.persistence.JdbcStateMachinePersist;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineModelConfigurer;
import org.springframework.statemachine.persist.DefaultStateMachinePersister;
import org.springframework.statemachine.persist.StateMachinePersister;

@Configuration
@EnableStateMachineFactory
public class WorkflowEngineConfiguration extends StateMachineConfigurerAdapter<String, String> {
    private final JdbcWorkflowModelFactory modelFactory;

    public WorkflowEngineConfiguration(JdbcWorkflowModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    @Override
    public void configure(StateMachineModelConfigurer<String, String> model) throws Exception {
        model.withModel()
                .factory(modelFactory);
    }

    @Bean
    StateMachinePersister<String, String, String> workflowPersister(JdbcStateMachinePersist jdbcStateMachinePersist) {
        return new DefaultStateMachinePersister<>(jdbcStateMachinePersist);
    }
}
