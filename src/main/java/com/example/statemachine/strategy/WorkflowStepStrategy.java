package com.example.statemachine.strategy;

import com.example.statemachine.dto.workflow.WorkflowStepContext;

/** A reusable step selected by its configured workflow_action.action_name. */
public interface WorkflowStepStrategy {
    String actionName();

    /** Complete the work synchronously; propagate failures to prevent progress from being persisted. */
    void execute(WorkflowStepContext context) throws Exception;
}
