---
title: 'Send Notification Job Through WAHA with Attempt History'
type: 'feature'
created: '2026-08-20'
status: 'ready-for-dev'
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

- [ ] Task 1: Flyway migration V25 — create `notification_attempts` table and alter `notification_jobs` (AC: 3, 4, 5, 6)
  - [ ] Create `V25__create_notification_attempts.sql`
  - [ ] Add columns `sent_at TIMESTAMPTZ`, `attempt_count INT NOT NULL DEFAULT 0`, `next_attempt_at TIMESTAMPTZ`, `max_attempts INT NOT NULL DEFAULT 3` to `notification_jobs`
  - [ ] Create `notification_attempts` table: `id UUID PK`, `job_id UUID FK→notification_jobs`, `attempt_number INT`, `status VARCHAR(16)`, `attempted_at TIMESTAMPTZ`, `response_detail VARCHAR(512)`, `trace_id VARCHAR(64)`
  - [ ] Add index on `notification_attempts(job_id)`

- [ ] Task 2: Extend `NotificationJobStatus` enum (AC: 4, 6)
  - [ ] Add `SENT`, `EXHAUSTED` to existing `PENDING`, `ROUTING_FAILED` in `NotificationJobStatus.java`

- [ ] Task 3: Update `NotificationJobEntity` with new columns (AC: 4, 5, 6)
  - [ ] Add `sentAt`, `attemptCount`, `nextAttemptAt`, `maxAttempts` fields
  - [ ] Add getters and a `void markSent(Instant)` mutator and `void markAttemptFailed(Instant nextAttemptAt, int maxAttempts)` mutator that update status/counts atomically in the entity

- [ ] Task 4: Create `NotificationAttemptEntity` (AC: 3)
  - [ ] `com.syncro.notification.infrastructure.NotificationAttemptEntity`
  - [ ] Fields: `id UUID`, `jobId UUID`, `attemptNumber int`, `status String`, `attemptedAt Instant`, `responseDetail String`, `traceId String`
  - [ ] `@PrePersist` sets `attemptedAt` if null

- [ ] Task 5: Create `NotificationAttemptRepository` (AC: 3)
  - [ ] `JpaRepository<NotificationAttemptEntity, UUID>` in infrastructure package

- [ ] Task 6: Create `WahaProperties` config class (AC: 7)
  - [ ] `@ConfigurationProperties(prefix = "syncro.waha")` with `url` and `apiKey` fields
  - [ ] Bind to existing `syncro.waha.url` and `syncro.waha.api-key` in `application.yml`
  - [ ] Annotate with `@EnableConfigurationProperties` in a config class or `@ConfigurationPropertiesScan`

- [ ] Task 7: Create `WahaClient` HTTP client (AC: 2, 7)
  - [ ] `com.syncro.notification.infrastructure.WahaClient`
  - [ ] Use Spring `RestClient` (Boot 4, not WebClient/RestTemplate)
  - [ ] POST to `{syncro.waha.url}/api/sendText` with JSON body `{ "chatId": "<phone>@c.us", "text": "<rendered>", "session": "default" }`
  - [ ] Set `X-Api-Key` header from `WahaProperties.apiKey` — NEVER log the key
  - [ ] Return `WahaClient.Result` record with `boolean success`, `int httpStatus`, `String detail`
  - [ ] Log only: `[WAHA][traceId={}] send attempt jobId={} status={}`

- [ ] Task 8: Create `WahaTemplateRenderer` service (AC: 2)
  - [ ] `com.syncro.notification.application.WahaTemplateRenderer`
  - [ ] Load active `alert_notification` template via `WahaTemplateRepository`
  - [ ] Substitute variables: `{machineCode}`, `{machineName}`, `{plantCode}`, `{machineGroup}`, `{sparepartName}`, `{thresholdPercent}`, `{currentCount}`, `{alertTime}` — using `String.replace()` with values from `SparepartAlertEntity` + joined `MachineEntity` + `PlantEntity`
  - [ ] Throw `WahaTemplateNotFoundException` if no active template found
  - [ ] Do not call this class for `ROUTING_FAILED` jobs

- [ ] Task 9: Create `NotificationDispatchService` — core dispatch logic (AC: 1–8)
  - [ ] `com.syncro.notification.application.NotificationDispatchService`
  - [ ] Inject: `NotificationJobRepository`, `NotificationAttemptRepository`, `WahaClient`, `WahaTemplateRenderer`, `SparepartAlertRepository`, `Clock`
  - [ ] Method `void dispatch(NotificationJobEntity job)`:
    1. Render template (catch `WahaTemplateNotFoundException` → mark EXHAUSTED, record attempt with detail)
    2. Call `WahaClient.send()`
    3. On success: save `NotificationAttemptEntity(status=SENT)`, call `job.markSent(now)`, save job
    4. On failure: save `NotificationAttemptEntity(status=FAILED)`, call `job.markAttemptFailed(nextAttemptAt, maxAttempts)`, save job
  - [ ] `nextAttemptAt` backoff = `now + (2^attemptCount) minutes`, capped at 60 minutes

- [ ] Task 10: Create `NotificationWorker` scheduled poller (AC: 1, 9)
  - [ ] `com.syncro.notification.application.NotificationWorker`
  - [ ] `@Scheduled(fixedDelayString = "${syncro.notification.worker.poll-interval-ms:30000}")`
  - [ ] Query: `findTop10ByStatusAndNextAttemptAtBeforeOrNextAttemptAtIsNull(PENDING, now)` — add this custom query to `NotificationJobRepository`
  - [ ] For each job: call `NotificationDispatchService.dispatch(job)` inside a try/catch to prevent one failure from stopping the batch
  - [ ] Log worker run start/end with count processed

- [ ] Task 11: Add `syncro.notification.worker.poll-interval-ms` to `application.yml` (AC: 1)
  - [ ] Default: `30000` (30 seconds) as inline default in `@Scheduled`; document in yml with comment

- [ ] Task 12: Write `NotificationDispatchServiceTest` unit test (AC: 3–8)
  - [ ] Happy path: mock `WahaClient` returns success → job `SENT`, attempt row `SENT`
  - [ ] WAHA failure < maxAttempts: job stays `PENDING`, attempt row `FAILED`, `nextAttemptAt` set
  - [ ] WAHA failure at maxAttempts: job `EXHAUSTED`, attempt row `FAILED`, no `nextAttemptAt`
  - [ ] Missing template: job `EXHAUSTED`, attempt row `FAILED` with detail

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

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List
