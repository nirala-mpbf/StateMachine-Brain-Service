package com.example.statemachine.service;

import com.example.statemachine.config.GsonConfiguration;
import com.example.statemachine.dto.api.*;
import com.example.statemachine.dto.persistence.WorkflowIdempotencyClaim;
import com.example.statemachine.dto.workflow.WorkflowCommand;
import com.example.statemachine.enums.IdempotencyClaimOutcome;
import com.example.statemachine.enums.WorkflowOperation;
import com.example.statemachine.exception.WorkflowException;
import com.example.statemachine.repository.RedisWorkflowIdempotencyRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRedisIdempotencyTest {
    private final WorkflowCommand command = new WorkflowCommand(
            new WorkflowCommandMetadata("CREATE", "req-1", "APP-101", null, "key-1", "onboarding"), WorkflowOperation.CREATE);

    @Test
    void savesSuccessfulResponseOnlyAfterTransactionalServiceReturns() {
        RecordingRepository repository = new RecordingRepository();
        WorkflowCommandService commandService = new WorkflowCommandService(null, repository) {
            @Override
            public ResponseEntity<WorkflowResponse> executeTransactionalCommand(WorkflowCommand command, String owner) {
                repository.databaseReturned = true;
                return response();
            }
        };
        var gson = new GsonConfiguration().gson();
        WorkflowIdempotencyService service = new WorkflowIdempotencyService(repository, commandService, gson);
        assertEquals(201, service.execute(command, "fingerprint").getStatusCode().value());
        assertTrue(repository.completed);
        assertEquals(response().getBody(), gson.fromJson(repository.responseJson, WorkflowResponse.class));
    }

    @Test
    void conflictingAndInProgressClaimsNeverExecuteTheCommand() {
        RecordingRepository repository = new RecordingRepository();
        WorkflowIdempotencyService service = new WorkflowIdempotencyService(repository, null, new GsonConfiguration().gson());
        repository.outcome = IdempotencyClaimOutcome.CONFLICT;
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(WorkflowException.class,
                () -> service.execute(command, "fingerprint")).code());
        repository.outcome = IdempotencyClaimOutcome.IN_PROGRESS;
        assertEquals("IDEMPOTENCY_IN_PROGRESS", assertThrows(WorkflowException.class,
                () -> service.execute(command, "fingerprint")).code());
        assertFalse(repository.completed);
    }

    @Test
    void redisCompletionFailureDoesNotReleaseTheClaimAfterDatabaseSuccess() {
        RecordingRepository repository = new RecordingRepository();
        repository.failCompletion = true;
        WorkflowCommandService commandService = new WorkflowCommandService(null, repository) {
            @Override
            public ResponseEntity<WorkflowResponse> executeTransactionalCommand(WorkflowCommand command, String owner) {
                repository.databaseReturned = true;
                return response();
            }
        };
        WorkflowIdempotencyService service = new WorkflowIdempotencyService(repository, commandService, new GsonConfiguration().gson());
        assertThrows(IllegalStateException.class, () -> service.execute(command, "fingerprint"));
        assertTrue(repository.databaseReturned);
        assertFalse(repository.retryable);
    }

    @Test
    void confirmedJdbcRollbackMakesTheSameClaimRetryable() {
        RecordingRepository repository = new RecordingRepository();
        WorkflowExecutionService failingExecution = new WorkflowExecutionService(null, null, null, null, null, null, null) {
            @Override
            public WorkflowDetails createWorkflow(String referenceId, String workflowKey) {
                throw new IllegalStateException("Database write failed");
            }
        };
        WorkflowCommandService service = new WorkflowCommandService(failingExecution, repository);
        TransactionTemplate transaction = new TransactionTemplate(new TestTransactionManager());
        assertThrows(IllegalStateException.class,
                () -> transaction.execute(status -> service.executeTransactionalCommand(command, "owner")));
        assertTrue(repository.retryable);
        assertFalse(repository.completed);
    }

    @Test
    void jdbcCommitDoesNotMarkTheClaimRetryable() {
        RecordingRepository repository = new RecordingRepository();
        WorkflowCommandService service = new WorkflowCommandService(null, repository) {
            @Override
            public ResponseEntity<WorkflowResponse> dispatchWorkflowCommand(WorkflowCommand command) {
                return response();
            }
        };
        new TransactionTemplate(new TestTransactionManager()).execute(status -> service.executeTransactionalCommand(command, "owner"));
        assertFalse(repository.retryable);
    }

    @Test
    void unknownJdbcCommitOutcomeDoesNotMakeTheClaimRetryable() {
        RecordingRepository repository = new RecordingRepository();
        WorkflowCommandService service = new WorkflowCommandService(null, repository) {
            @Override
            public ResponseEntity<WorkflowResponse> dispatchWorkflowCommand(WorkflowCommand command) {
                return response();
            }
        };
        TestTransactionManager manager = new TestTransactionManager() {
            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                throw new TransactionSystemException("Commit outcome unknown");
            }
        };
        assertThrows(TransactionSystemException.class, () -> new TransactionTemplate(manager)
                .execute(status -> service.executeTransactionalCommand(command, "owner")));
        assertFalse(repository.retryable);
    }

    private ResponseEntity<WorkflowResponse> response() {
        return ResponseEntity.status(201).body(new WorkflowResponse(
                new WorkflowResponseMetadata("CREATE", "req-1", "APP-101", null, "key-1", "onboarding", "SUCCESS",
                        Instant.parse("2026-11-04T06:00:00Z"), false),
                new WorkflowDetails("APP-101", "onboarding", 1, "OTP_VERIFICATION", "VERIFY_OTP", "PROFILE_DETAILS",
                        false, List.of(new AvailableWorkflowTransition("VERIFY_OTP", "PROFILE_DETAILS")), null), null));
    }

    private static class RecordingRepository extends RedisWorkflowIdempotencyRepository {
        private IdempotencyClaimOutcome outcome = IdempotencyClaimOutcome.CLAIMED;
        private boolean databaseReturned;
        private boolean completed;
        private boolean retryable;
        private boolean failCompletion;
        private String responseJson;

        RecordingRepository() { super(null); }

        @Override
        public WorkflowIdempotencyClaim claim(String key, String fingerprint, String owner) {
            return new WorkflowIdempotencyClaim(outcome, null, null);
        }

        @Override
        public void complete(String key, String owner, String json, int status) {
            assertTrue(databaseReturned);
            if (failCompletion) throw new IllegalStateException("Redis unavailable after commit");
            completed = true;
            responseJson = json;
        }

        @Override
        public void markRetryable(String key, String owner) { retryable = true; }
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
