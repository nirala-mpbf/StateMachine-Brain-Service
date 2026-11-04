package com.example.statemachine.dto.workflow;

import com.example.statemachine.dto.api.WorkflowCommandMetadata;

/** Immutable request context; step implementations do not decide workflow order or target states. */
public record WorkflowStepContext(
        WorkflowCommandMetadata metadata,
        Integer definitionId,
        String workflowKey,
        int definitionVersion
) { }
