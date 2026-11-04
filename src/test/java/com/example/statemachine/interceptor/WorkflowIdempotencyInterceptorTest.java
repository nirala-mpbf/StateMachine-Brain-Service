package com.example.statemachine.interceptor;

import com.example.statemachine.config.GsonConfiguration;
import com.example.statemachine.config.WorkflowIdempotencyConfiguration;
import com.example.statemachine.dto.api.WorkflowCommandMetadata;
import com.example.statemachine.dto.api.WorkflowResponse;
import com.example.statemachine.dto.api.WorkflowResponseMetadata;
import com.example.statemachine.dto.persistence.WorkflowIdempotencyClaim;
import com.example.statemachine.dto.workflow.WorkflowCommand;
import com.example.statemachine.enums.IdempotencyClaimOutcome;
import com.example.statemachine.enums.WorkflowOperation;
import com.example.statemachine.enums.WorkflowRequestFingerprintFunction;
import com.example.statemachine.exception.WorkflowException;
import com.example.statemachine.repository.RedisWorkflowIdempotencyRepository;
import com.example.statemachine.service.WorkflowCommandGateway;
import com.example.statemachine.service.WorkflowCommandService;
import com.example.statemachine.service.WorkflowIdempotencyService;
import com.google.gson.Gson;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowIdempotencyInterceptorTest {
    private AnnotationConfigApplicationContext context;
    private WorkflowCommandGateway gateway;
    private final Gson gson = new GsonConfiguration().gson();
    private final RecordingRepository repository = new RecordingRepository();
    private int executions;
    private boolean failExecution;
    private boolean committed;
    private boolean executionWasTransactional;

    @BeforeEach
    void startContext() {
        context = new AnnotationConfigApplicationContext();
        context.register(WorkflowIdempotencyConfiguration.class, WorkflowIdempotencyInterceptor.class,
                WorkflowCommandGateway.class, WorkflowIdempotencyService.class);
        context.registerBean(Gson.class, () -> gson);
        context.registerBean(RedisWorkflowIdempotencyRepository.class, () -> repository);
        context.registerBean(WorkflowCommandService.class, () -> new WorkflowCommandService(null, repository) {
            @Override
            public ResponseEntity<WorkflowResponse> dispatchWorkflowCommand(WorkflowCommand command) {
                executions++;
                executionWasTransactional = TransactionSynchronizationManager.isActualTransactionActive();
                if (failExecution) throw new IllegalStateException("Database write failed");
                return response(command);
            }
        });
        context.registerBean("transactionManager", AbstractPlatformTransactionManager.class,
                () -> new AbstractPlatformTransactionManager() {
                    @Override protected Object doGetTransaction() { return new Object(); }
                    @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
                    @Override protected void doCommit(DefaultTransactionStatus status) { committed = true; }
                    @Override protected void doRollback(DefaultTransactionStatus status) { }
                });
        context.refresh();
        gateway = context.getBean(WorkflowCommandGateway.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @ParameterizedTest
    @EnumSource(value = WorkflowOperation.class, names = {"CREATE", "SUBMIT_EVENT"})
    void proxiedMutationsClaimRedisAndStoreResponseAfterCommit(WorkflowOperation operation) {
        WorkflowCommand command = command(operation);
        assertTrue(AopUtils.isAopProxy(gateway));
        assertEquals(response(command), gateway.executeWorkflowCommand(command));
        assertEquals(1, repository.claims);
        assertEquals(WorkflowRequestFingerprintFunction.SHA_256.apply(command, gson), repository.fingerprint);
        assertEquals(1, executions);
        assertTrue(executionWasTransactional);
        assertTrue(committed);
        assertTrue(repository.completed);
        assertFalse(repository.retryable);
    }

    @Test
    void readBypassesRedisAndTheMutationTransaction() {
        WorkflowCommand command = command(WorkflowOperation.GET_STATE);
        assertEquals(response(command), gateway.executeWorkflowCommand(command));
        assertEquals(1, executions);
        assertEquals(0, repository.claims);
        assertFalse(executionWasTransactional);
        assertFalse(repository.completed);
    }

    @ParameterizedTest
    @EnumSource(value = IdempotencyClaimOutcome.class, names = {"CONFLICT", "IN_PROGRESS"})
    void rejectedClaimsDoNotExecuteOrCommit(IdempotencyClaimOutcome outcome) {
        repository.outcome = outcome;
        WorkflowException exception = assertThrows(WorkflowException.class,
                () -> gateway.executeWorkflowCommand(command(WorkflowOperation.CREATE)));
        assertEquals("IDEMPOTENCY_" + outcome.name(), exception.code());
        assertEquals(0, executions);
        assertFalse(committed);
        assertFalse(repository.completed);
    }

    @Test
    void replayReturnsSavedResultWithoutExecutingTheCommand() {
        repository.outcome = IdempotencyClaimOutcome.REPLAY;
        var result = gateway.executeWorkflowCommand(command(WorkflowOperation.CREATE));
        assertEquals(201, result.getStatusCode().value());
        assertTrue(result.getBody().metadata().idempotencyReplayed());
        assertEquals("request-1", result.getBody().metadata().requestId());
        assertEquals(0, executions);
        assertFalse(committed);
        assertFalse(repository.completed);
    }

    @Test
    void rollbackThroughTheInterceptorKeepsTheClaimRetryable() {
        failExecution = true;
        assertThrows(IllegalStateException.class,
                () -> gateway.executeWorkflowCommand(command(WorkflowOperation.CREATE)));
        assertTrue(repository.retryable);
        assertFalse(committed);
        assertFalse(repository.completed);
    }

    private WorkflowCommand command(WorkflowOperation operation) {
        return new WorkflowCommand(new WorkflowCommandMetadata(operation.name(), "request-1", "APP-101",
                operation == WorkflowOperation.SUBMIT_EVENT ? "VERIFY_OTP" : null,
                operation == WorkflowOperation.GET_STATE ? null : "key-1",
                operation == WorkflowOperation.CREATE ? "onboarding" : null), operation);
    }

    private ResponseEntity<WorkflowResponse> response(WorkflowCommand command) {
        var metadata = command.metadata();
        return ResponseEntity.status(command.operation() == WorkflowOperation.CREATE ? 201 : 200)
                .body(new WorkflowResponse(new WorkflowResponseMetadata(metadata.operation(), metadata.requestId(),
                        metadata.referenceId(), metadata.event(), metadata.idempotencyKey(), metadata.workflowKey(),
                        "SUCCESS", Instant.parse("2026-11-04T06:00:00Z"), false), null, null));
    }

    private class RecordingRepository extends RedisWorkflowIdempotencyRepository {
        private IdempotencyClaimOutcome outcome = IdempotencyClaimOutcome.CLAIMED;
        private int claims;
        private String fingerprint;
        private String owner;
        private boolean completed;
        private boolean retryable;

        RecordingRepository() { super(null); }

        @Override
        public WorkflowIdempotencyClaim claim(String key, String requestFingerprint, String ownerToken) {
            claims++;
            fingerprint = requestFingerprint;
            owner = ownerToken;
            return new WorkflowIdempotencyClaim(outcome,
                    gson.toJson(response(command(WorkflowOperation.CREATE)).getBody()), 201);
        }

        @Override
        public void complete(String key, String ownerToken, String json, int status) {
            assertTrue(committed, "Redis completion must follow the JDBC commit");
            assertEquals(owner, ownerToken);
            completed = true;
        }

        @Override
        public void markRetryable(String key, String ownerToken) {
            assertEquals(owner, ownerToken);
            retryable = true;
        }
    }
}
