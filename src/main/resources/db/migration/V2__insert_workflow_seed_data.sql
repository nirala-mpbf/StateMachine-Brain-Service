-- Identity columns generate IDs; seed relationships are resolved by business names and codes.
INSERT INTO workflow_brain.workflow_definition (workflow_key, version, status)
VALUES ('onboarding', 1, 'ACTIVE');

INSERT INTO workflow_brain.workflow_state (state_code, initial_state, terminal_state, fk_id_workflow_definition)
SELECT seed.state_code, seed.initial_state, seed.terminal_state, definition.id
FROM workflow_brain.workflow_definition definition
CROSS JOIN (VALUES
    ('OTP_VERIFICATION', TRUE, FALSE),
    ('PROFILE_DETAILS', FALSE, FALSE),
    ('ACCOUNT_STATEMENT_UPLOAD', FALSE, FALSE),
    ('UDYAM_DOCUMENT_UPLOAD', FALSE, FALSE),
    ('COMPLETED', FALSE, TRUE)
) AS seed(state_code, initial_state, terminal_state)
WHERE definition.workflow_key = 'onboarding' AND definition.version = 1;

INSERT INTO workflow_brain.workflow_action (action_name, description) VALUES
('recordStateEntry', 'Record the last entered state in extended state'),
('recordStateExit', 'Record the last exited state in extended state'),
('observeState', 'Log state activity at DEBUG level'),
('recordTransition', 'Record the last processed transition event in extended state');

INSERT INTO workflow_brain.workflow_transition
(event_code, transition_kind, fk_id_workflow_definition, fk_id_source_workflow_state, fk_id_target_workflow_state)
SELECT seed.event_code, 'EXTERNAL', definition.id, source_state.id, target_state.id
FROM workflow_brain.workflow_definition definition
CROSS JOIN (VALUES
    ('OTP_VERIFICATION', 'VERIFY_OTP', 'PROFILE_DETAILS'),
    ('PROFILE_DETAILS', 'UPDATE_PROFILE', 'ACCOUNT_STATEMENT_UPLOAD'),
    ('ACCOUNT_STATEMENT_UPLOAD', 'UPLOAD_ACCOUNT_STATEMENT', 'UDYAM_DOCUMENT_UPLOAD'),
    ('UDYAM_DOCUMENT_UPLOAD', 'UPLOAD_UDYAM_DOCUMENT', 'COMPLETED')
) AS seed(source_state, event_code, target_state)
JOIN workflow_brain.workflow_state source_state
    ON source_state.fk_id_workflow_definition = definition.id AND source_state.state_code = seed.source_state
JOIN workflow_brain.workflow_state target_state
    ON target_state.fk_id_workflow_definition = definition.id AND target_state.state_code = seed.target_state
WHERE definition.workflow_key = 'onboarding' AND definition.version = 1;

INSERT INTO workflow_brain.workflow_state_action (phase, execution_order, fk_id_workflow_state, fk_id_workflow_action)
SELECT binding.phase, 0, state.id, action.id
FROM workflow_brain.workflow_state state
CROSS JOIN (VALUES
    ('recordStateEntry', 'ENTRY'),
    ('recordStateExit', 'EXIT'),
    ('observeState', 'STATE')
) AS binding(action_name, phase)
JOIN workflow_brain.workflow_action action ON action.action_name = binding.action_name;

INSERT INTO workflow_brain.workflow_transition_action (execution_order, fk_id_workflow_transition, fk_id_workflow_action)
SELECT 0, transition.id, action.id
FROM workflow_brain.workflow_transition transition
JOIN workflow_brain.workflow_action action ON action.action_name = 'recordTransition';
