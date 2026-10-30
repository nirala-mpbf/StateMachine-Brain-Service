package com.example.statemachine.util;

/** Keys persisted in machine-context variables; keep values stable for existing workflow snapshots. */
public final class WorkflowContextKeys {
    private WorkflowContextKeys() {}

    public static final String LAST_ENTERED_STATE = "lastEnteredState";
    public static final String LAST_EXITED_STATE = "lastExitedState";
    public static final String LAST_PROCESSED_EVENT = "lastProcessedEvent";
    public static final String PENDING_EVENTS = "pendingEvents";
}
