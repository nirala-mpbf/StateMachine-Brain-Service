package com.example.statemachine.constants;

/** Fixed workflow, transport and fingerprint values. Keep fingerprint settings stable for saved retries. */
public final class WorkflowConstants {
    private WorkflowConstants() { }

    public static final String FINGERPRINT_ALGORITHM = "SHA-256";
    public static final String DEFAULT_WORKFLOW_KEY = "onboarding";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String ACTION_PHASE_ENTRY = "ENTRY";
    public static final String ACTION_PHASE_EXIT = "EXIT";
    public static final String ACTION_PHASE_STATE = "STATE";
    public static final String WORKFLOW_STEP_CONTEXT_HEADER = "workflowStepContext";
    public static final String API_PATH_PREFIX = "/api/";
    public static final String WORKFLOWS_PATH = "/api/workflows";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
}
