---
title: 'Escalate Alert Notifications by Responsibility Level'
type: 'feature'
created: '2026-08-20'
status: 'done'
baseline_commit: '1861a7c'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/spec-5-2-queue-initial-waha-notification-for-open-alert.md'
  - '_bmad-output/implementation-artifacts/spec-5-3-send-notification-job-through-waha-with-attempt-history.md'
warnings: []
---

# Story 5.4: Escalate Alert Notifications by Responsibility Level

Status: review

## Story

As a system,
I want unacknowledged alerts to escalate through responsibility levels,
so that higher levels are notified when no one responds.

## Acceptance Criteria

1. A scheduled escalation worker polls `notification_jobs` for jobs where `status=SENT`, the alert is still `OPEN`, and `sentAt + escalationIntervalMs <= now`. These are eligible for escalation.
2. The worker determines the next escalation level by walking the ordered sequence `TECHNICIAN → STAFF → LEADER → SPV → MANAGER` from the current job's `escalationLevel`.
3. When a next level exists, the worker looks up the responsible user for that level via `MachineResponsibilityRepository.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc`. The `machineId` is resolved from the alert via `SparepartAlertRepository`.
4. If a recipient is found and has a `whatsappNumber`, a new `NotificationJobEntity` with `status=PENDING` is inserted using idempotency key `alertId + "::" + nextLevel.name()`. A `DataIntegrityViolationException` on insert (duplicate) is silently swallowed — the job already exists.
5. If no recipient is found or `whatsappNumber` is null for the next level, a `NotificationJobEntity` with `status=ROUTING_FAILED` and a descriptive `errorDetail` is inserted (same idempotency key). `DataIntegrityViolationException` is silently swallowed.
6. After queuing the next level job (or recording a routing failure), the escalation worker marks the current job `status=ESCALATED` so it is not re-processed.
7. If the current job is already at the highest configured level (`MANAGER`) and the alert is still `OPEN`, the worker marks the job `status=ESCALATED` with no further queuing. No error is raised; this is the end of the chain.
8. The default escalation interval is 15 minutes, configurable via `syncro.notification.escalation.interval-ms` (default `900000`). The worker uses `Instant.now(clock)` — backend time only, never frontend-driven.
9. The escalation worker poll interval is configurable via `syncro.notification.escalation.poll-interval-ms` (default `60000`).
10. Escalation is skipped entirely if the alert `status` is `ACKNOWLEDGED` or `RESOLVED` at poll time — the current job is left in `SENT` status (not `ESCALATED`) so no spurious state mutation occurs.
11. Each escalation worker run is idempotent: optimistic locking (`@Version` on `NotificationJobEntity`) prevents two concurrent workers from escalating the same job twice. A `ObjectOptimisticLockingFailureException` is caught per-job and logged at WARN level; the job is skipped in that poll cycle.
12. The `ESCALATED` status must be added to `NotificationJobStatus` enum and the `notification_jobs.status` column check must accommodate the new value (VARCHAR(24) already sufficient).
13. A Flyway migration `V27__add_escalated_job_status_and_escalation_index.sql` adds an index on `notification_jobs(alert_id, escalation_level)` (already exists as unique constraint — no new index needed) and an index on `(status, sent_at)` for efficient escalation polling.
14. WAHA credentials must never be logged. Log only `[EscalationWorker][traceId=...]` prefixed messages.

## Tasks / Subtasks

- [x] Task 1: Extend `NotificationJobStatus` enum with `ESCALATED` (AC: 6, 7, 12)
  - [x] Add `ESCALATED` to `com.syncro.notification.domain.NotificationJobStatus`
  - [x] Verify `notification_jobs.status` column VARCHAR(24) accommodates "ESCALATED" (10 chars — fits, no migration needed for column)

- [x] Task 2: Flyway migration V27 — add escalation polling index (AC: 13)
  - [x] Create `V27__add_escalation_polling_index.sql`
  - [x] Add `CREATE INDEX idx_notification_jobs_status_sent_at ON notification_jobs (status, sent_at);`

- [x] Task 3: Add `sentAt` query method to `NotificationJobRepository` (AC: 1)
  - [x] Add JPQL query `findSentJobsDueForEscalation(@Param("cutoff") Instant cutoff)` — selects jobs where `status = SENT` and `sentAt <= :cutoff`, ordered by `sentAt asc`, limit 10
  - [x] Accept `cutoff` as parameter so the service controls interval math

- [x] Task 4: Create `EscalationWorker` — scheduled component (AC: 1, 8, 9, 11, 14)
  - [x] Create `com.syncro.notification.application.EscalationWorker`
  - [x] Annotate `@Component`, inject `NotificationJobRepository`, `EscalationService`, `Clock`
  - [x] `@Scheduled(fixedDelayString = "${syncro.notification.escalation.poll-interval-ms:60000}")`
  - [x] `poll()` calls `findSentJobsDueForEscalation(cutoff)` where `cutoff = Instant.now(clock).minus(intervalMs, MILLIS)`
  - [x] Loop: call `escalationService.escalate(job)` per job; catch `ObjectOptimisticLockingFailureException` per job (log WARN, skip); catch generic `Exception` per job (log ERROR, skip)

- [x] Task 5: Create `EscalationService` — core escalation logic (AC: 2, 3, 4, 5, 6, 7, 10, 11)
  - [x] Create `com.syncro.notification.application.EscalationService`
  - [x] Inject: `NotificationJobRepository`, `SparepartAlertRepository`, `MachineResponsibilityRepository`, `AuthUserRepository`, `Clock`
  - [x] Define ordered escalation sequence as `private static final List<ResponsibilityLevel> ESCALATION_ORDER = List.of(TECHNICIAN, STAFF, LEADER, SPV, MANAGER)`
  - [x] `@Transactional` method `escalate(NotificationJobEntity job)` with all branches implemented
  - [x] Uses `responsibility.getUserId()` directly (no lazy load on `getUser()`)

- [x] Task 6: Add `markEscalated()` mutator to `NotificationJobEntity` (AC: 6, 7)
  - [x] Add `public void markEscalated(Instant now)` — sets `this.status = NotificationJobStatus.ESCALATED; this.updatedAt = now;`
  - [x] Consistent with existing `markAttemptFailed`, `markSent`, `markExhausted` pattern

- [x] Task 7: Wire config properties in `application.yml` (AC: 8, 9)
  - [x] Added `syncro.notification.escalation.interval-ms: 900000` (15 minutes default)
  - [x] Added `syncro.notification.escalation.poll-interval-ms: 60000`
  - [x] `@Value("${syncro.notification.escalation.interval-ms:900000}")` in `EscalationWorker`

- [x] Task 8: Tests (AC: 1–11)
  - [x] Unit test `EscalationServiceTest` — 8 tests: happy-path, end of chain at MANAGER, alert ACKNOWLEDGED skips, alert RESOLVED skips, no assignment routing failure, no phone routing failure, duplicate insert swallowed, alert not found
  - [x] Unit test `EscalationWorkerTest` — 5 tests: jobs dispatched, no jobs, optimistic lock per-job caught, generic exception per-job caught, cutoff computed correctly
  - [x] All 13 unit tests pass (BUILD SUCCESS verified with Java 25 Temurin)

## Dev Notes

### Context: What Stories 5.1–5.3 Built

Story 5.2 introduced the `notification_jobs` table (V24) and `NotificationRoutingService`. It listens to `AlertOpenedEvent` via `@TransactionalEventListener(phase = AFTER_COMMIT)` and inserts a `TECHNICIAN`-level job. Idempotency key pattern: `alertId + "::" + levelName`.

Story 5.3 introduced `notification_attempts` (V25), the `@Version` optimistic locking column (V26), `NotificationWorker` (polls `PENDING` jobs, dispatches via `NotificationDispatchService`), and `SENT`/`EXHAUSTED` outcomes. The `NotificationJobEntity` now carries `sentAt`, `attemptCount`, `nextAttemptAt`, `maxAttempts`, and `version`.

**This story (5.4)** sits on top: it polls `SENT` jobs past the escalation window and queues the next-level job. It does NOT re-trigger WAHA sends — it only creates the next `PENDING` job row, which `NotificationWorker` picks up naturally.

### Critical: `ESCALATED` is a Terminal-for-Escalation Status, Not for Delivery

`ESCALATED` on a job means "this level's escalation responsibility has been handed off to the next level". The job's actual WAHA delivery result is still encoded in its `SENT` (delivered) or `EXHAUSTED` (delivery failed) path — `markEscalated()` is called *after* the job already reached `SENT`. Do not conflate `ESCALATED` with delivery failure.

### Idempotency Key Pattern — Must Match Story 5.2

`NotificationRoutingService` uses `event.alertId() + "::" + levelName` as the idempotency key, protected by `UNIQUE (alert_id, escalation_level)` on `notification_jobs`. The escalation worker must use the same pattern: `job.getAlertId() + "::" + nextLevel.name()`. Deviating will bypass the dedup constraint and create duplicate jobs.

### Escalation Order Is Defined in Code, Not DB

`ResponsibilityLevel` enum order in code: `TECHNICIAN, STAFF, LEADER, SPV, MANAGER`. The escalation service must use `List.of(...)` with the same order — do not rely on enum `.ordinal()` because enum ordinal is brittle if values are reordered in the future.

### Alert Status Check Must Use Fresh DB Read

The escalation worker must re-read the alert status at `escalate()` time — not from a cached value — because the alert may have been acknowledged between the worker's poll and the escalation attempt. Use `sparepartAlertRepository.findById(job.getAlertId())`.

### Optimistic Locking: Already in Place

`NotificationJobEntity` carries `@Version private long version` (added in V26). `ObjectOptimisticLockingFailureException` from Spring Data wraps JPA's `OptimisticLockException`. Catch at the **worker loop level** (per-job), not inside `EscalationService`, to keep the service transactional and clean.

### Do Not Extend `NotificationWorker` — Create a Separate `EscalationWorker`

`NotificationWorker` dispatches `PENDING` jobs to WAHA. Escalation logic is different enough (different poll query, different business rules, different status transition) that mixing them creates an entangled class. Keep concerns separated.

### `SparepartAlertRepository` Is Already Available

`com.syncro.alert.infrastructure.SparepartAlertRepository` extends `JpaRepository<SparepartAlertEntity, UUID>`. Use `findById(UUID)` — no new query needed.

### `AuthUserRepository` Pattern from `NotificationRoutingService`

`NotificationRoutingService` already calls `authUserRepository.findById(userId)` to get `whatsappNumber`. Reuse the same pattern in `EscalationService`. The `auth_users.whatsapp_number` column is nullable (added V23).

### Next Flyway Migration is V27

Latest applied migration is V26 (`V26__notification_job_idempotency_and_constraints.sql`). Next must be `V27__add_escalation_polling_index.sql`. Do not skip or reuse version numbers — Flyway will fail on checksum mismatch.

### `@Scheduled` Requires `@EnableScheduling`

Check that `@EnableScheduling` is present in the application (it was already needed by `NotificationWorker` in Story 5.3). Do not add it again if it already exists.

### No Frontend Changes in This Story

Story 5.6 owns the escalation timeline UI. This story is pure backend. Do not add any API endpoints, DTOs, or frontend components.

### `NotificationJobEntity` Mutator Style

Story 5.3 introduced `markAttemptFailed(Instant now, Instant nextAttemptAt)` and `markSent(Instant now)` as named mutators. Follow the same pattern: `markEscalated(Instant now)`. Never add a generic `setStatus(...)` setter — it would allow callers to bypass domain invariants.

### Project Structure Notes

- New files to create:
  - `syncro/apps/backend/src/main/java/com/syncro/notification/application/EscalationWorker.java`
  - `syncro/apps/backend/src/main/java/com/syncro/notification/application/EscalationService.java`
  - `syncro/apps/backend/src/main/resources/db/migration/V27__add_escalation_polling_index.sql`
- Files to modify:
  - `syncro/apps/backend/src/main/java/com/syncro/notification/domain/NotificationJobStatus.java` — add `ESCALATED`
  - `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobEntity.java` — add `markEscalated(Instant now)`
  - `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` — add `findSentJobsDueForEscalation`
  - `syncro/apps/backend/src/main/resources/application.yml` — add escalation config block
- Test files:
  - `syncro/apps/backend/src/test/java/com/syncro/notification/application/EscalationServiceTest.java`
  - `syncro/apps/backend/src/test/java/com/syncro/notification/application/EscalationWorkerTest.java`

### References

- `NotificationJobStatus` enum: [Source: notification/domain/NotificationJobStatus.java]
- `NotificationJobEntity` with `@Version`, `markAttemptFailed`, `markSent`: [Source: notification/infrastructure/NotificationJobEntity.java]
- `NotificationJobRepository.findPendingJobsDue` (poll pattern to follow): [Source: notification/infrastructure/NotificationJobRepository.java]
- `NotificationWorker` (`@Scheduled`, Clock injection, per-job exception isolation): [Source: notification/application/NotificationWorker.java]
- `NotificationDispatchService` (dispatch pattern, `@Transactional`): [Source: notification/application/NotificationDispatchService.java]
- `NotificationRoutingService` (idempotency key pattern, `AFTER_COMMIT` event, `DataIntegrityViolationException` swallow): [Source: notification/application/NotificationRoutingService.java]
- `MachineResponsibilityRepository.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc`: [Source: machine/infrastructure/MachineResponsibilityRepository.java:18]
- `ResponsibilityLevel` enum (`TECHNICIAN, STAFF, LEADER, SPV, MANAGER`): [Source: machine/domain/ResponsibilityLevel.java]
- `SparepartAlertEntity.getStatus()`, `getMachineId()`: [Source: alert/infrastructure/SparepartAlertEntity.java]
- `SparepartAlertStatus` enum (`OPEN, ACKNOWLEDGED, RESOLVED`): [Source: alert/domain/SparepartAlertStatus.java]
- Flyway latest V26: [Source: db/migration/V26__notification_job_idempotency_and_constraints.sql]
- `notification_jobs` schema (V24 + V25 + V26): unique constraint `uq_notification_jobs_alert_level (alert_id, escalation_level)`, `@Version` column, `sent_at` column
- Architecture escalation flow: [Source: _bmad-output/planning-artifacts/architecture.md#WAHA Escalation]

### Review Findings

- [x] [Review][Patch] Infinite retry — missing alert leaves job in SENT forever → mark ESCALATED [EscalationService.java:58]
- [x] [Review][Defer] Infinite retry — closed/resolved alert leaves job in SENT forever — deferred, behavior is correct per AC10; permanent closed-alert loop is addressed by alert lifecycle story
- [x] [Review][Patch] Infinite retry — invalid escalation level string leaves job in SENT [EscalationService.java:76]
- [x] [Review][Patch] currentIndex == -1 silently treated as end-of-chain, no error log [EscalationService.java:82]
- [x] [Review][Patch] JPQL uses string literal `'SENT'` instead of enum parameter — refactor risk [NotificationJobRepository.java:26]
- [x] [Review][Patch] Dead code `userWithPhone()` helper never called in test [EscalationServiceTest.java]
- [x] [Review][Patch] Fully-qualified import in EscalationWorker instead of top-level import [EscalationWorker.java:18]
- [x] [Review][Defer] ROUTING_FAILED jobs are never re-queried for escalation retry [EscalationService.java] — deferred, pre-existing design decision (ROUTING_FAILED is a terminal state by design)
- [x] [Review][Defer] `sentAt` column has no DB NOT NULL constraint despite being required for SENT-status jobs [NotificationJobEntity.java:52] — deferred, pre-existing

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-5

### Debug Log References

### Completion Notes List

- Used `responsibility.getUserId()` directly instead of `responsibility.getUser().getId()` to avoid lazy load on LAZY JPA relationship.
- `.mvn/jvm.config` created to suppress `EnableDynamicAgentLoading` warning from Mockito on Java 25.
- `findSentJobsDueForEscalation` uses `cutoff` parameter (worker computes `now - intervalMs`) rather than raw `now` to keep interval logic in the worker.
- `JPQL status = 'SENT'` used as string literal in query (enum stored as STRING in DB).
- 13 unit tests pass (8 EscalationServiceTest + 5 EscalationWorkerTest). BUILD SUCCESS verified with Java 25 Temurin.

### File List

- `syncro/apps/backend/src/main/java/com/syncro/notification/domain/NotificationJobStatus.java` (modified — added `ESCALATED`)
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobEntity.java` (modified — added `markEscalated(Instant now)`)
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` (modified — added `findSentJobsDueForEscalation`)
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/EscalationService.java` (new)
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/EscalationWorker.java` (new)
- `syncro/apps/backend/src/main/resources/application.yml` (modified — added escalation config block)
- `syncro/apps/backend/src/main/resources/db/migration/V27__add_escalation_polling_index.sql` (new)
- `syncro/apps/backend/src/main/java/com/syncro/.mvn/jvm.config` (new — suppress dynamic agent loading warning)
- `syncro/apps/backend/src/test/java/com/syncro/notification/application/EscalationServiceTest.java` (new)
- `syncro/apps/backend/src/test/java/com/syncro/notification/application/EscalationWorkerTest.java` (new)
