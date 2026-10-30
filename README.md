# State Machine Brain

A database-configured onboarding workflow using Spring Boot 4.1.1, Spring
StateMachine 4.0.2, NamedParameterJdbcTemplate, Flyway and PostgreSQL. No JPA.

The consuming service performs OTP verification, profile collection and document
uploads. It then submits the corresponding completed business event to this API.
This application coordinates progress; it does not verify OTPs or upload files.

```text
OTP_VERIFICATION --OTP_VERIFIED--> PROFILE_DETAILS
PROFILE_DETAILS --PROFILE_SUBMITTED--> ACCOUNT_STATEMENT_UPLOAD
ACCOUNT_STATEMENT_UPLOAD --ACCOUNT_STATEMENT_UPLOADED--> UDYAM_DOCUMENT_UPLOAD
UDYAM_DOCUMENT_UPLOAD --UDYAM_DOCUMENT_UPLOADED--> COMPLETED
```

## Run locally

Requires Java 25+ (Java 25 is the recommended runtime), Maven 3.9+ and PostgreSQL.
The optional Docker Compose file starts a dedicated development database on 55433,
so it does not use a PostgreSQL service already listening on 5432. Its credentials
are local development values only.

```powershell
docker compose -f compose.dev.yaml up -d --wait
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55433/workflow_brain'
$env:DB_USERNAME = 'workflow'
$env:DB_PASSWORD = 'workflow'
mvn spring-boot:run
```

If Maven/Java are not on PATH, configure JAVA_HOME and use your installed Maven's
full path. The server defaults to port 8080; set SERVER_PORT to change it.

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- OpenAPI YAML: http://localhost:8080/v3/api-docs.yaml

Stop a foreground app with Ctrl+C. Stop only this project's development database
with `docker compose -f compose.dev.yaml stop`. The database volume is retained.

## One business endpoint

All commands use **POST /api/workflows** and `Content-Type: application/json`.
Swagger/documentation routes are separate from the business endpoint.

Every request contains exactly `metadata` and `payload`. Both must be JSON
objects. Payload is currently `{}` for every operation. Unknown fields, duplicate
JSON keys, trailing JSON, nulls and inappropriate fields are rejected.

| Operation | Required metadata | Optional metadata | Success |
| --- | --- | --- | --- |
| CREATE | operation, requestId, referenceId | workflowKey (default onboarding), idempotencyKey | 201 |
| GET_STATE | operation, requestId, referenceId | None | 200 |
| SUBMIT_EVENT | operation, requestId, referenceId, event | idempotencyKey | 200 |

Names are case sensitive. requestId and idempotencyKey are nonblank strings up to
128 characters. referenceId and workflowKey allow 100 characters; event allows
100. One reference identifies one workflow instance in this application.

### Create

```json
{
  "metadata": {
    "operation": "CREATE",
    "requestId": "req-001",
    "referenceId": "APP-101",
    "workflowKey": "onboarding",
    "idempotencyKey": "APP-101-create"
  },
  "payload": {}
}
```

### Submit a completed business event

```json
{
  "metadata": {
    "operation": "SUBMIT_EVENT",
    "requestId": "req-002",
    "referenceId": "APP-101",
    "event": "OTP_VERIFIED",
    "idempotencyKey": "APP-101-otp"
  },
  "payload": {}
}
```

Example response (processedAt varies):

```json
{
  "metadata": {
    "operation": "SUBMIT_EVENT",
    "requestId": "req-002",
    "referenceId": "APP-101",
    "event": "OTP_VERIFIED",
    "idempotencyKey": "APP-101-otp",
    "workflowKey": "onboarding",
    "status": "SUCCESS",
    "processedAt": "2026-10-22T12:00:00Z",
    "idempotencyReplayed": false
  },
  "payload": {
    "referenceId": "APP-101",
    "workflowKey": "onboarding",
    "definitionVersion": 1,
    "currentState": "PROFILE_DETAILS",
    "nextEvent": "PROFILE_SUBMITTED",
    "nextState": "ACCOUNT_STATEMENT_UPLOAD",
    "completed": false,
    "availableTransitions": [
      {"event": "PROFILE_SUBMITTED", "nextState": "ACCOUNT_STATEMENT_UPLOAD"}
    ],
    "eventOutcome": "APPLIED",
    "pendingEvents": []
  }
}
```

Continue with PROFILE_SUBMITTED, ACCOUNT_STATEMENT_UPLOADED, then
UDYAM_DOCUMENT_UPLOADED. Each request advances exactly one business event; there
is no execute-all operation. An intentionally deferred event can be replayed as
part of handling a later incoming event if a custom definition configures that.

### Get progress

```json
{
  "metadata": {"operation": "GET_STATE", "requestId": "req-003", "referenceId": "APP-101"},
  "payload": {}
}
```

nextEvent and nextState are included when there is exactly one outgoing
transition. For branching workflows use availableTransitions; terminal states
have an empty list and completed=true. These are configured possibilities, not a
guarantee that a guard will permit the event.

## Database layout

All tables are in the `workflow_brain` schema. See [docs/DATABASE.md](docs/DATABASE.md)
for columns, relationships and SQL queries.

| Table | Stores |
| --- | --- |
| workflow_definition | Workflow identity, version and lifecycle status |
| workflow_state | Available states and initial/terminal flags |
| workflow_transition | Source state + event -> target state, transition kind, guard |
| workflow_action | Names/descriptions of registered Java actions |
| workflow_guard | Names/descriptions of registered Java guards |
| workflow_state_action | State entry/exit/activity action bindings and order |
| workflow_transition_action | Transition action bindings and order |
| workflow_deferred_event | Events postponed while in a state |
| workflow_instances | One reference's current state and serialized machine context |
| workflow_request_replay | Retry fingerprint, original successful response and status |
| workflow_transition_history | Applied events, source/target states and the request that applied them |

Flyway V1 is unchanged. V2 adds definitions, pins existing instances to
onboarding-v1, and preserves their references, states and timestamps. A preexisting
state outside the original five states fails the migration's foreign key instead
of silently rewriting customer progress. Existing rows with no machine_context
restore from current_state on first access; the next event saves a full context.

If a database was created manually without Flyway history, review its schema and
baseline it deliberately before migration. Do not enable baseline-on-migrate to
silently accept an unknown schema.

## How execution and persistence work

```text
WorkflowController -> WorkflowRequestValidator -> WorkflowCommandGateway
  -> WorkflowExecutionService -> StateMachineFactory
       -> JdbcWorkflowModelFactory -> JdbcWorkflowDefinitionRepository
       -> DefaultStateMachinePersister -> JdbcStateMachinePersist
                                        -> JdbcWorkflowInstanceRepository
```

The controller receives a command envelope. The gateway dispatches CREATE,
GET_STATE or SUBMIT_EVENT to createWorkflow, getWorkflowState or submitEvent. For CREATE and SUBMIT_EVENT, the model factory builds a fresh machine from the
versioned database definition. SUBMIT_EVENT locks the instance with SELECT FOR
UPDATE before restoring it. GET_STATE reads one saved state/context snapshot in
a read-only transaction without locking the row or starting a machine. The persister reads/writes a
versioned JSON snapshot using JDBC in the same transaction as workflow mutation.

Context format 1 supports flat, single-region machines, current state and JSON
extended variables. It does not claim support for hierarchical/parallel states,
history pseudostates or arbitrary Java object serialization. Additional formats
need an explicit codec and migration. Transport message headers are not stored.

Spring's transient deferred-event queue is not persisted. Configured deferred
event names are coalesced in extended state, persisted, and replayed when a
matching transition becomes available. Terminal completion discards pending
intent. Deferred payloads are empty under this API contract.

## Actions, guards and configuration

WorkflowBehaviorRegistry is the explicit Java strategy registry. Seeds include
recordStateEntry, recordStateExit, observeState and recordTransition. These record
small context values or log activity; no external business calls are made.

The seeded alwaysAllow guard adds no extra business condition. Real conditions
can be implemented as named Java guard strategies and referenced in definitions.
The API trusts the consuming service's business events; the seeded guard is not
OTP verification or authorization. Unknown action/guard names fail closed.

State and event names are database strings, so adding/reordering rules does not
require editing enums. A definition must have one initial state, at least one
terminal state, no outgoing transitions from terminal states, and no unreachable
states. Internal transitions keep the same state. Actions execute in configured
order; thrown action/guard errors roll back the request and return INTERNAL_ERROR.
A guard returning false returns GUARD_REJECTED. State activity is asynchronous
observation; keep transactional business decisions in guards and transition/entry/exit actions.

Treat published definitions as immutable. Make configuration changes through a
new Flyway migration that inserts a new definition version and its rows. CREATE
chooses the highest ACTIVE version; existing instances retain their definition_id
even after that version is RETIRED. A DRAFT definition is not used by CREATE.
Database permissions and reviewed migrations enforce this operational rule;
there is no configuration editing API.

Patterns used: command envelope/dispatch, factory, explicit action/guard
strategies, repository adapters, persistence adapter and a transactional gateway.
StateMachine lifecycle calls use the reactive API and await completion; the HTTP
API and JDBC access remain synchronous.

## Transition history

Flyway V3 adds workflow_transition_history. Existing V1/V2 migrations remain
unchanged. Applied events are recorded in the same database transaction as the
workflow state and, when provided, the saved idempotent response. If history
insertion fails, the workflow update and successful replay response roll back.

Each row records reference_id, definition_id, source_state, event_code,
target_state, request_id, idempotency_key and created_at. Internal transitions are
recorded even when source and target are equal. A deferred event gets a history
row when it is actually applied, using the request ID/key of the request that
caused its application. One request may therefore produce multiple history rows.

CREATE, GET_STATE, rejected events, deferred intent waiting in a queue and
idempotency replays do not add transition rows. Existing progress is preserved;
past transitions cannot be reconstructed from the latest state. There is no new
history HTTP endpoint. This is applied-transition history, not a full request or
business-document audit.

## Request and result logging

WorkflowRequestLoggingFilter uses SLF4J for /api/ requests. It logs receipt,
requestId, referenceId, operation/event, HTTP status, resulting state, event
outcome, idempotency replay, error code and duration. Exceptions retain stack
traces on the server. Raw request/response bodies, OTPs and document contents
are not logged. Metadata is bounded and control characters are sanitized.

Every API response carries X-Correlation-Id, a server-generated UUID for the
HTTP attempt. It is separate from the caller's requestId and idempotencyKey.
For malformed JSON without recoverable metadata, this correlation ID still
connects the received/completed/error logs. MDC is cleared/restored after the
request so identifiers cannot leak to the next request on that thread.

Example result summary:

```text
workflow_http_completed correlationId=... requestId=req-101 referenceId=APP-101 operation=SUBMIT_EVENT event=OTP_VERIFIED status=200 currentState=PROFILE_DETAILS eventOutcome=APPLIED idempotencyReplayed=false errorCode=null durationMs=24
```

## Code organization

- api: envelopes, validation and Swagger schemas/configuration.
- controller: the single business HTTP endpoint.
- exception: API failures and response mapping.
- definition: database-backed workflow configuration records/repository.
- statemachine: model factory, behavior registry, configuration and event processor.
- persistence: JDBC context, instance, retry and history repositories.
- service: command dispatch, read-only state retrieval and transactional mutations.
- logging: HTTP correlation and structured request/result summaries.

WorkflowEventProcessor submits one event and returns its previous state, event,
current state and outcome. It has no HTTP or database responsibility. The existing
listener-based classification is retained to preserve guard behavior and detect
internal transitions; comparing states alone would miss same-state transitions.
The framework-required model factory build methods retain their interface names.

## Idempotency and transactions

requestId is correlation only. Repeat a mutation with the same idempotencyKey to
receive its original successful HTTP status and payload. The response preserves
processedAt, echoes the retry's requestId, and sets idempotencyReplayed=true.
The replay is the original result, which may differ from the instance's latest
state. GET_STATE returns current progress.

Keys are global within this application. A SHA-256 fingerprint includes operation,
referenceId, event and normalized workflowKey; requestId is excluded. Payload is
validated empty. An omitted workflowKey and explicit onboarding are equivalent.
Different inputs with the same key produce IDEMPOTENCY_CONFLICT.

A key reservation commits separately, then SELECT FOR UPDATE locks it. Workflow
mutation and successful response storage commit together. Concurrent same-key
requests serialize; concurrent updates to one reference also serialize. Failed
requests do not store success, but leave the key bound to the original inputs so
an identical request can retry. Keys have no automatic expiration.

Only JDBC work can roll back with this transaction. If future actions call an
external service, use an outbox or an independently idempotent integration.

## Errors and client migration

Failures use the same envelope with payload=null and error.code/description.
Recoverable request metadata is echoed; malformed JSON may have no metadata.

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | INVALID_REQUEST | Invalid envelope, fields or event |
| 404 | WORKFLOW_NOT_FOUND | Reference does not exist |
| 404 | WORKFLOW_DEFINITION_NOT_FOUND | No active definition for requested key |
| 404 | ENDPOINT_NOT_FOUND | Removed or unknown route |
| 409 | WORKFLOW_ALREADY_EXISTS | Reference already exists |
| 409 | EVENT_NOT_ALLOWED | Event is not allowed in the current state |
| 409 | GUARD_REJECTED | Existing event processing did not observe a matching transition after its checks |
| 409 | IDEMPOTENCY_CONFLICT | Retry key used with different inputs |
| 500 | INTERNAL_ERROR | Unexpected failure, with details only in server logs |

Unsupported HTTP methods/content types return 405/415 with INVALID_REQUEST.
The old /workflows/create, /workflows/get, /workflows/otp/verify,
/workflows/submit-profile, /workflows/enter-account-statement and
/workflows/udyam-document routes have been removed. Update consuming clients to
the command envelope. Payload details now appear under payload in responses.

## Build

```shell
mvn clean package
java -jar target/state-machine-brain-0.0.1-SNAPSHOT.jar
```

Set the database environment variables described above before starting the JAR.
This repository currently does not contain automated test sources; a successful
package build checks compilation and packaging, not workflow behavior.

## Swagger and OpenAPI integration

Swagger UI includes request examples for CREATE, GET_STATE and each onboarding
event, plus response schemas and error codes. Try it out sends real requests to
the configured database.

For an MCP service or another API integration that supports OpenAPI, use the
machine-readable schema at `/v3/api-docs` (JSON) or `/v3/api-docs.yaml` (YAML).
The application exposes REST and OpenAPI; the MCP adapter/service is a separate
integration.

## Why metadata and payload are separate

metadata tells the brain which command to run, which application it affects,
and how to correlate/retry the call. payload contains the business inputs needed
by that command. For the current contract all business steps are performed by
the consuming service, so payload remains the required empty object {}.

This separation is an API convention, not a Spring StateMachine requirement.
It keeps request tracing and routing consistent as business inputs evolve. No
account-statement approval field or new guard rule is added by this refactor.
