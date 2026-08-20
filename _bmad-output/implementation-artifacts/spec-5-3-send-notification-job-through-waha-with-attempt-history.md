---
title: 'Send Notification Job Through WAHA with Attempt History'
type: 'feature'
created: '2026-08-20'
status: 'review'
baseline_commit: '758a1f2f442a4b2d77c10c264780a54d36b6214d'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/spec-5-2-queue-initial-waha-notification-for-open-alert.md'
warnings: []
---

# Story 5.3: Send Notification Job Through WAHA with Attempt History

Status: ready-for-dev

## Story

As a SUPER_ADMIN or MANAGE user,
I want WAHA sends to be tracked with attempt history,
so that failed WhatsApp notification delivery is visible and diagnosable.

## Acceptance Criteria

1. A scheduled worker polls `notification_jobs` rows with `status=PENDING` and processes them.
2. For each job, the worker renders the active `alert_notification` WAHA template with alert context data, then sends the rendered message to WAHA via HTTP POST.
3. Each send attempt is recorded as a `notification_attempts` row containing: `jobId`, `attemptNumber`, `status` (`SENT` or `FAILED`), `attemptedAt`, `responseDetail` (truncated to 512 chars), and `traceId`.
4. A successful WAHA response (HTTP 2xx) transitions the job `status` to `SENT` and sets `sentAt`.
5. A failed WAHA response (non-2xx or timeout) records the attempt as `FAILED`, increments `attemptCount` on the job, and sets `nextAttemptAt = now + backoff` when `attemptCount < maxAttempts`.
6. When `attemptCount >= maxAttempts`, the job `status` is set to `EXHAUSTED` and no further attempts are scheduled.
7. WAHA API key is read from config; it must never appear in logs. Log only `[WAHA]` or `[traceId=...]` prefixed messages.
8. Jobs with `status=ROUTING_FAILED` are never picked up by the worker.
9. The worker is idempotent: concurrent pollers cannot double-process the same job (use optimistic locking or `SELECT … FOR UPDATE SKIP LOCKED`).

## Tasks / Subtasks

- [x] Task 1: Flyway migration V25 — create `notification_attempts` table and alter `notification_jobs` (AC: 3, 4, 5, 6)
  - [x] Create `V25__create_notification_attempts.sql`
  - [x] Add columns `sent_at TIMESTAMPTZ`, `attempt_count INT NOT NULL DEFAULT 0`, `next_attempt_at TIMESTAMPTZ`, `max_attempts INT NOT NULL DEFAULT 3` to `notification_jobs`
  - [x] Create `notification_attempts` table: `id UUID PK`, `job_id UUID FK→notification_jobs`, `attempt_number INT`, `status VARCHAR(16)`, `attempted_at TIMESTAMPTZ`, `response_detail VARCHAR(512)`, `trace_id VARCHAR(64)`
  - [x] Add index on `notification_attempts(job_id)`

- [x] Task 2: Extend `NotificationJobStatus` enum (AC: 4, 6)
  - [x] Add `SENT`, `EXHAUSTED` to existing `PENDING`, `ROUTING_FAILED` in `NotificationJobStatus.java`

- [x] Task 3: Update `NotificationJobEntity` with new columns (AC: 4, 5, 6)
  - [x] Add `sentAt`, `attemptCount`, `nextAttemptAt`, `maxAttempts` fields
  - [x] Add getters and a `void markSent(Instant)` mutator and `void markAttemptFailed(Instant nextAttemptAt, int maxAttempts)` mutator that update status/counts atomically in the entity

- [x] Task 4: Create `NotificationAttemptEntity` (AC: 3)
  - [x] `com.syncro.notification.infrastructure.NotificationAttemptEntity`
  - [x] Fields: `id UUID`, `jobId UUID`, `attemptNumber int`, `status String`, `attemptedAt Instant`, `responseDetail String`, `traceId String`
  - [x] `@PrePersist` sets `attemptedAt` if null

- [x] Task 5: Create `NotificationAttemptRepository` (AC: 3)
  - [x] `JpaRepository<NotificationAttemptEntity, UUID>` in infrastructure package

- [x] Task 6: Create `WahaProperties` config class (AC: 7)
  - [x] `@ConfigurationProperties(prefix = "syncro.waha")` with `url` and `apiKey` fields
  - [x] Bind to existing `syncro.waha.url` and `syncro.waha.api-key` in `application.yml`
  - [x] Annotate with `@EnableConfigurationProperties` in a config class or `@ConfigurationPropertiesScan`

- [x] Task 7: Create `WahaClient` HTTP client (AC: 2, 7)
  - [x] `com.syncro.notification.infrastructure.WahaClient`
  - [x] Use Spring `RestClient` (Boot 4, not WebClient/RestTemplate)
  - [x] POST to `{syncro.waha.url}/api/sendText` with JSON body `{ "chatId": "<phone>@c.us", "text": "<rendered>", "session": "default" }`
  - [x] Set `X-Api-Key` header from `WahaProperties.apiKey` — NEVER log the key
  - [x] Return `WahaClient.Result` record with `boolean success`, `int httpStatus`, `String detail`
  - [x] Log only: `[WAHA][traceId={}] send attempt jobId={} status={}`

- [x] Task 8: Create `WahaTemplateRenderer` service (AC: 2)
  - [x] `com.syncro.notification.application.WahaTemplateRenderer`
  - [x] Load active `alert_notification` template via `WahaTemplateRepository`
  - [x] Substitute variables: `{machineCode}`, `{machineName}`, `{plantCode}`, `{machineGroup}`, `{sparepartName}`, `{thresholdPercent}`, `{currentCount}`, `{alertTime}` — using `String.replace()` with values from `SparepartAlertEntity` + joined `MachineEntity` + `PlantEntity`
  - [x] Throw `WahaTemplateRenderException` if no active template found
  - [x] Do not call this class for `ROUTING_FAILED` jobs

- [x] Task 9: Create `NotificationDispatchService` — core dispatch logic (AC: 1–8)
  - [x] `com.syncro.notification.application.NotificationDispatchService`
  - [x] Inject: `NotificationJobRepository`, `NotificationAttemptRepository`, `WahaClient`, `WahaTemplateRenderer`, `Clock`
  - [x] Method `void dispatch(NotificationJobEntity job)`: render template, call WAHA, save attempt, update job status
  - [x] `nextAttemptAt` backoff = `now + (2^attemptCount) minutes`, capped at 60 minutes

- [x] Task 10: Create `NotificationWorker` scheduled poller (AC: 1, 9)
  - [x] `com.syncro.notification.application.NotificationWorker`
  - [x] `@Scheduled(fixedDelayString = "${syncro.notification.worker.poll-interval-ms:30000}")`
  - [x] Query: `findPendingJobsDue(PENDING, now)` — custom query added to `NotificationJobRepository`
  - [x] For each job: call `NotificationDispatchService.dispatch(job)` inside a try/catch

- [x] Task 11: Add `syncro.notification.worker.poll-interval-ms` to `application.yml` (AC: 1)
  - [x] Default: `30000` (30 seconds) with environment variable override support

- [x] Task 12: Write `NotificationDispatchServiceTest` unit test (AC: 3–8)
  - [x] Happy path: mock `WahaClient` returns success → job `SENT`, attempt row `SENT`
  - [x] WAHA failure < maxAttempts: job stays `PENDING`, attempt row `FAILED`, `nextAttemptAt` set
  - [x] WAHA failure at maxAttempts: job `EXHAUSTED`, attempt row `FAILED`, no `nextAttemptAt`
  - [x] Missing template: job `EXHAUSTED`, attempt row `FAILED` with detail

## Dev Notes

### Existing Code to Build On

- `NotificationJobEntity` — `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobEntity.java`
  - Already has `alertId`, `escalationLevel`, `status`, `recipientPhone`, `idempotencyKey`, `traceId`
  - **Do not** add `@Version` (optimistic locking on the entity) — use `SKIP LOCKED` query-level approach instead to avoid stale object exceptions during concurrent polling
- `NotificationJobStatus` — `syncro/apps/backend/src/main/java/com/syncro/notification/domain/NotificationJobStatus.java`
  - Currently: `PENDING`, `ROUTING_FAILED` — add `SENT`, `EXHAUSTED`
- `WahaTemplateEntity` / `WahaTemplateRepository` — already exist in `notification/infrastructure/`
- `WahaTemplate.KNOWN_VARIABLES` — use this set as reference for substitution keys
- `application.yml` — `syncro.waha.url` and `syncro.waha.api-key` already bound; do NOT rename

### WAHA HTTP API Contract

- Endpoint: `POST {syncro.waha.url}/api/sendText`
- Request body (JSON):
  ```json
  { "chatId": "<recipientPhone>@c.us", "text": "<rendered body>", "session": "default" }
  ```
- Auth header: `X-Api-Key: <syncro.waha.api-key>`
- Success: HTTP 200/201
- Failure: non-2xx; log HTTP status + response body (first 512 chars only, no credentials)

### RestClient Pattern (Spring Boot 4)

```java
RestClient restClient = RestClient.builder()
    .baseUrl(wahaProperties.getUrl())
    .defaultHeader("X-Api-Key", wahaProperties.getApiKey())
    .build();

ResponseEntity<String> response = restClient.post()
    .uri("/api/sendText")
    .contentType(MediaType.APPLICATION_JSON)
    .body(payload)
    .retrieve()
    .onStatus(HttpStatusCode::isError, (req, res) -> {})  // suppress throw
    .toEntity(String.class);
```

Do not call `.retrieve().toBodilessEntity()` — capture response body for error detail logging.

### Alert Data for Template Rendering

To render the template, `WahaTemplateRenderer` needs to join:
- `SparepartAlertEntity` → has `machineId`, `machineSparepartInstallationId`, `thresholdPercentage`, `currentCount`, `consumedPercentage`, `createdAt`
- `MachineEntity` (via `machineId`) → has `code`, `name`, plant, group
- `MachineSparepartInstallationEntity` → has `sparepartId`
- `SparepartEntity` → has `name`

Use existing repositories; no new queries unless needed. If alert is not found, treat as `EXHAUSTED` with `errorDetail = "Alert not found"`.

### Flyway Migration Numbering

- Last applied: V24 (`create_notification_jobs`) — next is **V25**
- File: `V25__create_notification_attempts.sql`
- Alter `notification_jobs` to add new columns; existing rows default to `attempt_count=0`, `max_attempts=3`

### Test Pattern

Use `@ExtendWith(MockitoExtension.class)` for unit tests (no Spring context needed for dispatch service). Mock `WahaClient`, `WahaTemplateRenderer`, `NotificationJobRepository`, `NotificationAttemptRepository`, `Clock`.

Existing test pattern from `WahaTemplateControllerTest`:
- `@WebMvcTest` + `@Import({SecurityConfig, WahaTemplateExceptionHandler, JwtAuthenticationFilter, TimeConfig, TestJsonConfig})`
- For `NotificationDispatchServiceTest` — plain unit test, no `@WebMvcTest` needed

### Security Constraints

- `WahaProperties.apiKey` must never appear in:
  - Log output (use `[WAHA]` prefix only)
  - Exception messages
  - HTTP error responses
- `recipientPhone` may appear in logs at DEBUG level only (not INFO/WARN/ERROR)

### Project Structure Notes

- All new classes follow existing package structure: `domain/`, `application/`, `infrastructure/`, `api/`
- `WahaClient` goes in `infrastructure/` (external system adapter)
- `WahaTemplateRenderer` goes in `application/` (business logic)
- `NotificationDispatchService` goes in `application/`
- `NotificationWorker` goes in `application/`
- `WahaProperties` goes in a new `com.syncro.notification.config` or reuse `com.syncro.config` pattern — check existing config classes first
- `NotificationAttemptEntity` and `NotificationAttemptRepository` go in `infrastructure/`

### References

- WAHA template variables: `WahaTemplate.KNOWN_VARIABLES` [Source: notification/domain/WahaTemplate.java:15]
- WAHA config keys: `syncro.waha.url`, `syncro.waha.api-key` [Source: syncro/apps/backend/src/main/resources/application.yml:55-57]
- Outbox/job table pattern: PostgreSQL-backed outbox for Phase 1 [Source: _bmad-output/planning-artifacts/architecture.md:352]
- Notification attempt schema research: [Source: _bmad-output/planning-artifacts/research/technical-research-2026-05-25.md:1670]
- Story AC source: [Source: _bmad-output/planning-artifacts/epics.md:884-899]
- `NotificationJobEntity` existing fields: [Source: notification/infrastructure/NotificationJobEntity.java]
- `NotificationJobRepository` (extend here): [Source: notification/infrastructure/NotificationJobRepository.java]

### Review Findings

- [x] [Review][Patch] No idempotency lock — concurrent pollers can double-process the same job (AC 9) [NotificationJobRepository.java:13]
- [x] [Review][Patch] No UNIQUE constraint on (job_id, attempt_number) in notification_attempts — duplicate attempt records possible [V25__create_notification_attempts.sql]
- [x] [Review][Patch] No composite index on (status, next_attempt_at) on notification_jobs — full table scan on every poll [V25__create_notification_attempts.sql]
- [x] [Review][Patch] No CHECK (max_attempts > 0) constraint — maxAttempts=0 exhausts a job before first dispatch [V25__create_notification_attempts.sql]

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List
