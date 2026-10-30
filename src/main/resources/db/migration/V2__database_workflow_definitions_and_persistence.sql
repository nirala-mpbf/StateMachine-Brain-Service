-- V1 remains unchanged: existing references and current states are preserved.
CREATE TABLE workflow_brain.workflow_definition (
    definition_id VARCHAR(100) PRIMARY KEY,
    workflow_key VARCHAR(100) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (workflow_key, version)
);

CREATE TABLE workflow_brain.workflow_state (
    definition_id VARCHAR(100) NOT NULL REFERENCES workflow_brain.workflow_definition(definition_id),
    state_code VARCHAR(50) NOT NULL,
    initial_state BOOLEAN NOT NULL DEFAULT FALSE,
    terminal_state BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (definition_id, state_code),
    CHECK (NOT (initial_state AND terminal_state))
);

CREATE TABLE workflow_brain.workflow_action (
    action_code VARCHAR(100) PRIMARY KEY,
    description VARCHAR(500) NOT NULL
);

CREATE TABLE workflow_brain.workflow_guard (
    guard_code VARCHAR(100) PRIMARY KEY,
    description VARCHAR(500) NOT NULL
);

CREATE TABLE workflow_brain.workflow_transition (
    transition_id VARCHAR(150) PRIMARY KEY,
    definition_id VARCHAR(100) NOT NULL REFERENCES workflow_brain.workflow_definition(definition_id),
    source_state VARCHAR(50) NOT NULL,
    event_code VARCHAR(100) NOT NULL,
    target_state VARCHAR(50) NOT NULL,
    transition_kind VARCHAR(20) NOT NULL CHECK (transition_kind IN ('EXTERNAL', 'INTERNAL')),
    guard_code VARCHAR(100) REFERENCES workflow_brain.workflow_guard(guard_code),
    FOREIGN KEY (definition_id, source_state) REFERENCES workflow_brain.workflow_state(definition_id, state_code),
    FOREIGN KEY (definition_id, target_state) REFERENCES workflow_brain.workflow_state(definition_id, state_code),
    UNIQUE (definition_id, source_state, event_code),
    CHECK (transition_kind <> 'INTERNAL' OR source_state = target_state)
);

CREATE TABLE workflow_brain.workflow_state_action (
    definition_id VARCHAR(100) NOT NULL,
    state_code VARCHAR(50) NOT NULL,
    action_code VARCHAR(100) NOT NULL REFERENCES workflow_brain.workflow_action(action_code),
    phase VARCHAR(10) NOT NULL CHECK (phase IN ('ENTRY', 'EXIT', 'STATE')),
    execution_order INTEGER NOT NULL DEFAULT 0 CHECK (execution_order >= 0),
    FOREIGN KEY (definition_id, state_code) REFERENCES workflow_brain.workflow_state(definition_id, state_code),
    PRIMARY KEY (definition_id, state_code, phase, execution_order)
);

CREATE TABLE workflow_brain.workflow_transition_action (
    transition_id VARCHAR(150) NOT NULL REFERENCES workflow_brain.workflow_transition(transition_id),
    action_code VARCHAR(100) NOT NULL REFERENCES workflow_brain.workflow_action(action_code),
    execution_order INTEGER NOT NULL DEFAULT 0 CHECK (execution_order >= 0),
    PRIMARY KEY (transition_id, execution_order)
);

CREATE TABLE workflow_brain.workflow_deferred_event (
    definition_id VARCHAR(100) NOT NULL,
    state_code VARCHAR(50) NOT NULL,
    event_code VARCHAR(100) NOT NULL,
    FOREIGN KEY (definition_id, state_code) REFERENCES workflow_brain.workflow_state(definition_id, state_code),
    PRIMARY KEY (definition_id, state_code, event_code)
);

INSERT INTO workflow_brain.workflow_definition (definition_id, workflow_key, version, status)
VALUES ('onboarding-v1', 'onboarding', 1, 'ACTIVE');

INSERT INTO workflow_brain.workflow_state (definition_id, state_code, initial_state, terminal_state) VALUES
('onboarding-v1', 'OTP_VERIFICATION', TRUE, FALSE),
('onboarding-v1', 'PROFILE_DETAILS', FALSE, FALSE),
('onboarding-v1', 'ACCOUNT_STATEMENT_UPLOAD', FALSE, FALSE),
('onboarding-v1', 'UDYAM_DOCUMENT_UPLOAD', FALSE, FALSE),
('onboarding-v1', 'COMPLETED', FALSE, TRUE);

INSERT INTO workflow_brain.workflow_action (action_code, description) VALUES
('recordStateEntry', 'Record the last entered state in extended state'),
('recordStateExit', 'Record the last exited state in extended state'),
('observeState', 'Log state activity at DEBUG level'),
('recordTransition', 'Record the last processed transition event in extended state');

INSERT INTO workflow_brain.workflow_guard (guard_code, description)
VALUES ('alwaysAllow', 'No additional business condition; the caller supplies completed business events');

INSERT INTO workflow_brain.workflow_transition
(transition_id, definition_id, source_state, event_code, target_state, transition_kind, guard_code) VALUES
('onboarding-v1-otp', 'onboarding-v1', 'OTP_VERIFICATION', 'OTP_VERIFIED', 'PROFILE_DETAILS', 'EXTERNAL', 'alwaysAllow'),
('onboarding-v1-profile', 'onboarding-v1', 'PROFILE_DETAILS', 'PROFILE_SUBMITTED', 'ACCOUNT_STATEMENT_UPLOAD', 'EXTERNAL', 'alwaysAllow'),
('onboarding-v1-statement', 'onboarding-v1', 'ACCOUNT_STATEMENT_UPLOAD', 'ACCOUNT_STATEMENT_UPLOADED', 'UDYAM_DOCUMENT_UPLOAD', 'EXTERNAL', 'alwaysAllow'),
('onboarding-v1-udyam', 'onboarding-v1', 'UDYAM_DOCUMENT_UPLOAD', 'UDYAM_DOCUMENT_UPLOADED', 'COMPLETED', 'EXTERNAL', 'alwaysAllow');

INSERT INTO workflow_brain.workflow_state_action (definition_id, state_code, action_code, phase, execution_order)
SELECT definition_id, state_code, 'recordStateEntry', 'ENTRY', 0 FROM workflow_brain.workflow_state;
INSERT INTO workflow_brain.workflow_state_action (definition_id, state_code, action_code, phase, execution_order)
SELECT definition_id, state_code, 'recordStateExit', 'EXIT', 0 FROM workflow_brain.workflow_state;
INSERT INTO workflow_brain.workflow_state_action (definition_id, state_code, action_code, phase, execution_order)
SELECT definition_id, state_code, 'observeState', 'STATE', 0 FROM workflow_brain.workflow_state;
INSERT INTO workflow_brain.workflow_transition_action (transition_id, action_code, execution_order)
SELECT transition_id, 'recordTransition', 0 FROM workflow_brain.workflow_transition;

ALTER TABLE workflow_brain.workflow_instances ADD COLUMN definition_id VARCHAR(100);
UPDATE workflow_brain.workflow_instances SET definition_id = 'onboarding-v1';
ALTER TABLE workflow_brain.workflow_instances ALTER COLUMN definition_id SET NOT NULL;
ALTER TABLE workflow_brain.workflow_instances ADD COLUMN machine_context TEXT;
ALTER TABLE workflow_brain.workflow_instances ADD COLUMN context_format_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE workflow_brain.workflow_instances ADD CONSTRAINT instance_definition_fk
    FOREIGN KEY (definition_id) REFERENCES workflow_brain.workflow_definition(definition_id);
ALTER TABLE workflow_brain.workflow_instances ADD CONSTRAINT instance_state_fk
    FOREIGN KEY (definition_id, current_state) REFERENCES workflow_brain.workflow_state(definition_id, state_code);
ALTER TABLE workflow_brain.workflow_instances ADD CONSTRAINT context_format_check CHECK (context_format_version = 1);

CREATE TABLE workflow_brain.workflow_request_replay (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_fingerprint VARCHAR(64) NOT NULL,
    response_json TEXT,
    http_status INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    CHECK ((response_json IS NULL AND http_status IS NULL AND completed_at IS NULL)
        OR (response_json IS NOT NULL AND http_status BETWEEN 200 AND 299 AND completed_at IS NOT NULL))
);
