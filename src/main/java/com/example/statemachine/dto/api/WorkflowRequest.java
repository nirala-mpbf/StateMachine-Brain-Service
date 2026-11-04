package com.example.statemachine.dto.api;

/** Command envelope for creating, reading or requesting execution of a workflow step. */
public record WorkflowRequest(WorkflowRequestMetadata metadata, EmptyWorkflowPayload payload) { }
