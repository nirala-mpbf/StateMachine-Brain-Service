package com.example.statemachine.exception;

import com.example.statemachine.api.WorkflowApi.ApiError;
import com.example.statemachine.api.WorkflowApi.RequestMetadata;
import com.example.statemachine.api.WorkflowApi.Response;
import com.example.statemachine.api.WorkflowApi.ResponseMetadata;
import com.example.statemachine.logging.WorkflowRequestLoggingFilter;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;

@RestControllerAdvice
public class WorkflowExceptionHandler {
    public static final String METADATA_ATTRIBUTE =
            WorkflowExceptionHandler.class.getName() + ".metadata";
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response> handle(Exception ex, HttpServletRequest request) {
        HttpStatus status;
        String code;
        String message;
        if (ex instanceof WorkflowFailure failure) {
            status = failure.status();
            code = failure.code();
            message = failure.getMessage();
        } else if (ex instanceof DuplicateKeyException) {
            status = HttpStatus.CONFLICT;
            code = "WORKFLOW_ALREADY_EXISTS";
            message = "This referenceId already has a workflow.";
        } else if (ex instanceof HttpMessageNotReadableException) {
            status = HttpStatus.BAD_REQUEST;
            code = "INVALID_REQUEST";
            message = "Provide a valid JSON request body.";
        } else if (ex instanceof HttpMediaTypeNotSupportedException) {
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            code = "INVALID_REQUEST";
            message = "Use Content-Type application/json.";
        } else if (ex instanceof HttpRequestMethodNotSupportedException) {
            status = HttpStatus.METHOD_NOT_ALLOWED;
            code = "INVALID_REQUEST";
            message = "Use POST /api/workflows.";
        } else if (ex instanceof NoResourceFoundException) {
            status = HttpStatus.NOT_FOUND;
            code = "ENDPOINT_NOT_FOUND";
            message = "Endpoint not found.";
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            code = "INTERNAL_ERROR";
            message = "An unexpected error occurred.";
            LOG.error("Workflow request failed", ex);
        }
        var m = (RequestMetadata) request.getAttribute(METADATA_ATTRIBUTE);
        var metadata =
                new ResponseMetadata(
                        m == null ? null : m.operation(),
                        m == null ? null : m.requestId(),
                        m == null ? null : m.referenceId(),
                        m == null ? null : m.event(),
                        m == null ? null : m.idempotencyKey(),
                        m == null ? null : m.workflowKey(),
                        "FAILED",
                        Instant.now(),
                        null);
        var body = new Response(metadata, null, new ApiError(code, message));
        request.setAttribute(WorkflowRequestLoggingFilter.RESPONSE_ATTRIBUTE, body);
        return ResponseEntity.status(status).body(body);
    }
}
