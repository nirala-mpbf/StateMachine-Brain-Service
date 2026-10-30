CREATE TABLE workflow_brain.workflow_transition_history (
    history_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference_id VARCHAR(100) NOT NULL,
    definition_id VARCHAR(100) NOT NULL,
    source_state VARCHAR(50) NOT NULL,
    event_code VARCHAR(100) NOT NULL,
    target_state VARCHAR(50) NOT NULL,
    request_id VARCHAR(128),
    idempotency_key VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (reference_id) REFERENCES workflow_brain.workflow_instances(reference_id),
    FOREIGN KEY (definition_id) REFERENCES workflow_brain.workflow_definition(definition_id)
);

CREATE INDEX idx_workflow_history_reference
ON workflow_brain.workflow_transition_history (reference_id, created_at);
