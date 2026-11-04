package com.example.statemachine.command;

import com.example.statemachine.dto.workflow.WorkflowStepContext;
import com.example.statemachine.strategy.WorkflowStepStrategy;

/** Encapsulates one step invocation and delegates business behavior to the selected strategy. */
public record WorkflowStepCommand(WorkflowStepStrategy strategy, WorkflowStepContext context) {
    public void execute() throws Exception {
        strategy.execute(context);
    }
}
