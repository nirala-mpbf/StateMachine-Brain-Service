package com.example.statemachine.exception;

import org.springframework.http.HttpStatus;

public class WorkflowFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public WorkflowFailure(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static WorkflowFailure invalid(String message) {
        return new WorkflowFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    public static WorkflowFailure conflict(String code, String message) {
        return new WorkflowFailure(HttpStatus.CONFLICT, code, message);
    }
}
