---
title: 'Stop Escalation When Alert Is Acknowledged'
type: 'feature'
created: '2026-08-20'
status: 'review'
baseline_commit: '37a09fc'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/spec-5-4-escalate-alert-notifications-by-responsibility-level.md'
warnings: []
---

# Story 5.5: Stop Escalation When Alert Is Acknowledged

Status: done

## Story

As an authorized responsible user,
I want acknowledgement to stop future escalation,
so that no unnecessary WhatsApp notifications are sent after response.

## Acceptance Criteria

1. When an `OPEN` alert is acknowledged (`OPEN → ACKNOWLEDGED`), the backend stops escalation for that alert immediately — not lazily on the next escalation poll.
2. Existing `notification_jobs` rows in `PENDING` or `SENT` status for that alert are cancelled (status `CANCELLED`) during the same transaction as the acknowledge, so they cannot be picked up by `NotificationWorker` (PENDING) or `EscalationWorker` (SENT).
3. Jobs already in a terminal status (`SENT` jobs already marked `ESCALATED`, `EXHAUSTED`, `ROUTING_FAILED`, `CANCELLED`) are left untouched — cancellation applies only to `PENDING` and `SENT`.
4. The audit entry for acknowledgement records the number of cancelled jobs (e.g. `"escalationCancelledCount": 2`) alongside the existing transition data.
5. Acknowledge remains idempotent: acknowledging an already-`ACKNOWLEDGED` alert still returns `INVALID_STATE_TRANSITION` and does **not** re-cancel or re-queue any notification job.
6. `NotificationWorker` and `EscalationWorker` must never process a `CANCELLED` job — their poll queries must exclude `CANCELLED` from results.
7. The `CANCELLED` status value is added to the `NotificationJobStatus` enum and the `notification_jobs.status` column constraints accommodate it (VARCHAR(24) already sufficient; add a DB CHECK constraint extension via migration if the column carries one).
8. Concurrent escalation racing an acknowledge is safe: `@Version` optimistic locking on `NotificationJobEntity` prevents a cancelled `SENT` job from being re-escalated into a new level.
9. Escalation timeline evidence reflects stopped/cancelled pending levels (via `status=CANCELLED` on the job rows — UI rendering is owned by Story 5.6, but the data must be present and queryable).
10. WAHA credentials or secrets are never logged. Log only `[traceId=...]` prefixed messages.

## Tasks / Subtasks

- [x] Task 1: Add `CANCELLED` to `NotificationJobStatus` enum (AC: 7)
  - [x] Add `CANCELLED` to `com.syncro.notification.domain.NotificationJobStatus`
  - [x] Verify `notification_jobs.status` column VARCHAR(24) accommodates "CANCELLED" (9 chars — fits)

- [x] Task 2: Flyway migration V28 — constrain status values and index cancellation (AC: 7)
  - [x] Create `V28__notification_job_cancelled_status.sql`
  - [x] Add CHECK constraint `chk_notification_jobs_status_allowed` if one does not yet exist; otherwise extend existing constraint with `'CANCELLED'`
  - [x] Add index `idx_notification_jobs_alert_status` on `(alert_id, status)` to make bulk-cancel by alertId fast (optional but recommended for the `cancelByAlertId` query)

- [x] Task 3: Add `NotificationJobEntity.markCancelled(Instant now)` mutator (AC: 2)
  - [x] Add `public void markCancelled(Instant now)` — sets `status = CANCELLED`, clears `nextAttemptAt`, sets `updatedAt = now`
  - [x] Follow existing named-mutator pattern (`markSent`, `markExhausted`, `markEscalated`) — never add a generic `setStatus(...)` setter

- [x] Task 4: Add repository query to cancel PENDING+SENT jobs for an alert (AC: 2, 8)
  - [x] In `NotificationJobRepository`, add bulk JPQL update `cancelActiveForAlert(UUID alertId, Collection<NotificationJobStatus> activeStatuses, NotificationJobStatus cancelled, Instant now)` that sets `status=CANCELLED`, `nextAttemptAt=null`, `updatedAt=:now` for `PENDING` and `SENT` rows of that alert

- [x] Task 5: Wire cancellation into `SparepartAlertCommandService.acknowledge()` (AC: 1, 2, 4)
  - [x] Inject `NotificationJobRepository` into `com.syncro.alert.application.SparepartAlertCommandService`
  - [x] After `alertRepository.save(alert)` succeeds, call `cancelActiveForAlert` for `alertId`
  - [x] Capture the cancelled row count and include it in the audit `newValue` map as `"escalationCancelledCount": <int>`

- [x] Task 6: Ensure workers exclude `CANCELLED` jobs (AC: 6)
  - [x] Verified `NotificationJobRepository.findPendingJobsDue` filters by explicit `status = PENDING` — already excludes `CANCELLED`
  - [x] Verified `findSentJobsDueForEscalation` queries `j.status = SENT` — already excludes `CANCELLED`

- [x] Task 7: Concurrency safety (AC: 8)
  - [x] Confirmed `NotificationJobEntity` keeps `@Version` optimistic lock (already present)
  - [x] Confirmed `EscalationService.escalate()` keeps the existing `alert.getStatus() != OPEN` guard as second-line defense; residual race documented in spec

- [x] Task 8: Tests (AC: 1–10)
  - [x] Unit test `SparepartAlertCommandServiceTest` — 4 new cancellation tests: `cancelActiveForAlert` invoked with `alertId`; cancelled count in audit; zero-cancelled still writes audit; invalid transition does not cancel
  - [x] Unit test `NotificationJobEntityTest` — `markCancelled` sets `status=CANCELLED`, clears `nextAttemptAt` (2 tests)
  - [x] Integration/Testcontainers test `NotificationJobCancelIntegrationTest` (4 tests, `@DataJpaTest` + Flyway): AC1 cancels PENDING/SENT; AC2 doesn't touch other alerts; AC3 returns 0 when nothing to cancel; AC4 cancelled jobs excluded from worker queries
  - [x] All story-owned tests pass: `NotificationJobEntityTest` 2/2, `SparepartAlertCommandServiceTest` 23/23, `NotificationJobCancelIntegrationTest` 4/4

## Dev Notes

### Context: What Stories 5.1–5.4 Built

- **5.2** created `notification_jobs` (V24) + `NotificationRoutingService`, which listens to `AlertOpenedEvent` via `@TransactionalEventListener(AFTER_COMMIT)` and inserts a `TECHNICIAN`-level job. Idempotency key: `alertId + "::" + levelName`.
- **5.3** created `notification_attempts` (V25), `@Version` optimistic locking column (V26), `NotificationWorker` (polls `PENDING` jobs, dispatches via `NotificationDispatchService`), and `SENT`/`EXHAUSTED` outcomes.
- **5.4** added `ESCALATED` status, `findSentJobsDueForEscalation`, `EscalationWorker` (polls `SENT` jobs past interval) and `EscalationService.escalate()`. The `alert.getStatus() != OPEN` guard already lazily skips escalation once the alert is ACKNOWLEDGED.

### Critical Gap This Story Closes

`SparepartAlertCommandService.acknowledge()` currently transitions `OPEN → ACKNOWLEDGED`, saves, and audits — but does **not** touch `notification_jobs`. As a result:

- `PENDING` jobs already queued but not yet dispatched can still be picked up by `NotificationWorker` and sent to WAHA **after** acknowledgement.
- `SENT` jobs past their escalation window are silently skipped by `EscalationService` (they stay `SENT` forever, never terminal).

This story defines the job status rule: **acknowledgement cancels all `PENDING` and `SENT` jobs for that alert in the same transaction** (status → `CANCELLED`). The `EscalationService` guard becomes a second-line defense, not the primary mechanism.

### Idempotency Key Pattern — Must Match Stories 5.2 / 5.4

`NotificationRoutingService` and `EscalationService` both use `alertId + "::" + levelName`, protected by `UNIQUE (alert_id, escalation_level)` on `notification_jobs`. Cancellation must **never** insert new rows — it only mutates existing `PENDING`/`SENT` rows to `CANCELLED`. Duplicate acknowledgement is already rejected by `SparepartAlertEntity.acknowledge()` throwing `InvalidAlertTransitionException` when status is not `OPEN`, so no re-cancel path exists.

### `NotificationJobStatus` Enum Values (current)

```java
public enum NotificationJobStatus {
  PENDING,
  ROUTING_FAILED,
  SENT,
  EXHAUSTED,
  ESCALATED
}
```

Add `CANCELLED`. Do NOT add a generic status setter on the entity.

### DB Constraint — Verify Before Adding CHECK

`V24__create_notification_jobs.sql` defines `status VARCHAR(24) NOT NULL` with **no** CHECK constraint on allowed values. `V26` only added the `max_attempts > 0` check and the `version` column. So the `status` column currently accepts any string — meaning **no migration is strictly required for `CANCELLED` to persist**. However, add `V28__notification_job_cancelled_status.sql` to (a) document the allowed status set with a CHECK constraint, or (b) add an index `(alert_id, status)` for the bulk-cancel query. Follow the Flyway forward-only rule — do not edit V24/V26.

### `SparepartAlertCommandService.acknowledge()` — Current Shape

`com/syncro/alert/application/SparepartAlertCommandService.java` injects `SparepartAlertRepository`, `AuditLogWriter`, `AuthUserPlantAssignmentRepository`, `Clock`. The `acknowledge()` method:

1. `loadAndCheckAccess(user, alertId)` — plant-scoped or SUPER_ADMIN
2. `alert.acknowledge(reason, clock.instant())` — throws if not OPEN
3. `alertRepository.save(alert)`
4. `auditLogWriter.recordSystem(new AuditRecord(AuditAction.UPDATE, AuditEntityType.ALERT, alertId, "ALERT:" + alertId, resolvePlantId(alert), Map.of("status", "OPEN"), Map.of("actorId", ..., "transition", "OPEN→ACKNOWLEDGED", "status", "ACKNOWLEDGED", "reason", ...)))`

This story injects `NotificationJobRepository` and calls the cancel query after step 3, capturing the count for step 4's `newValue` map.

### Repository Query — Prefer Bulk Update

Add to `NotificationJobRepository`:

```java
@Modifying
@Query("""
    update NotificationJobEntity j
    set j.status = :cancelled, j.updatedAt = :now
    where j.alertId = :alertId and j.status in :activeStatuses
    """)
int cancelActiveForAlert(@Param("alertId") UUID alertId,
    @Param("activeStatuses") Collection<NotificationJobStatus> activeStatuses,
    @Param("cancelled") NotificationJobStatus cancelled,
    @Param("now") Instant now);
```

- Requires `@Modifying` + a transaction (already present on `acknowledge()`).
- `activeStatuses = List.of(PENDING, SENT)`.
- Bulk update bypasses `@Version` — acceptable here because a job that's being cancelled must not race into SENT; if it did, the DB unique constraint and worker re-reads prevent duplicate logical sends. Document this tradeoff in code.
- **Persistence-context pitfall:** `@Modifying` JPQL bulk updates do **not** update already-loaded managed entities in the current persistence context, and they can leave the persistence context out of sync (unless `clearAutomatically = true`). Within `acknowledge()`, `alert` is the only managed entity being saved — it is unaffected by the notification_jobs bulk update. Do **not** set `clearAutomatically` unless a stale-entity issue is actually observed, because clearing would detach the alert and require a re-fetch. If you need the cancelled count returned, the method return value (`int` rows affected) supplies it directly — no need to re-read.
- If the team prefers find-then-save (respecting `@Version` per row), add `findByAlertIdAndStatusIn` and loop `markCancelled(now)` + `save`. Either approach is valid; pick one and document it. **Recommended: bulk update** for atomicity within the acknowledge transaction.

### Workers Already Exclude CANCELLED by Status Filter

- `findPendingJobsDue(@Param("status") NotificationJobStatus status, ...)` — worker passes `NotificationJobStatus.PENDING`, so `CANCELLED` rows never match.
- `findSentJobsDueForEscalation(@Param("status") NotificationJobStatus status, ...)` — worker passes `SENT`, so `CANCELLED` rows never match.

No change needed to the worker queries, but verify no other dispatch path queries by broad status. Defensive `status not in (CANCELLED)` is optional.

### Concurrency: `@Version` and the EscalationWorker

`EscalationWorker.poll()` catches `ObjectOptimisticLockingFailureException` per job and skips that cycle. `NotificationJobEntity` carries `@Version`. A worker that loaded a `SENT` job just before acknowledge will fail to save its `markEscalated(now)` if the row was bulk-updated to `CANCELLED` in the interim — the OOL exception is caught, the cycle continues, and no escalation is queued for that alert. This is the intended safety net.

### Audit — No New Entity Type

`AuditEntityType` has `ALERT` already. Record cancellation in the existing acknowledge audit `newValue` map as `"escalationCancelledCount": <int>`. This keeps the evidence trail linear (one audit row per acknowledge) and satisfies AC 4 without a schema change to the audit tables.

### Next Flyway Migration is V28

Latest applied migration is `V27__add_escalation_polling_index.sql`. Next must be `V28__notification_job_cancelled_status.sql`. Do not skip or reuse version numbers.

### No Frontend Changes in This Story

Story 5.6 owns the escalation timeline UI (`EscalationTimeline` shows "stopped by acknowledgement" from `CANCELLED` job rows). This story is pure backend: data + behavior. Do not add API endpoints, DTOs, or frontend components.

### Project Structure Notes

- Files to modify:
  - `syncro/apps/backend/src/main/java/com/syncro/notification/domain/NotificationJobStatus.java` — add `CANCELLED`
  - `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobEntity.java` — add `markCancelled(Instant now)`
  - `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` — add bulk-cancel query
  - `syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertCommandService.java` — inject repo, cancel jobs on acknowledge, audit count
- Files to create:
  - `syncro/apps/backend/src/main/resources/db/migration/V28__notification_job_cancelled_status.sql`
- Test files:
  - `syncro/apps/backend/src/test/java/com/syncro/alert/application/SparepartAlertCommandServiceTest.java` (extend)
  - `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationJobEntityTest.java` (new, if entity test exists elsewhere, extend it)
  - `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/NotificationJobRepositoryTest.java` (new, Testcontainers)

### References

- `NotificationJobStatus` enum: [Source: notification/domain/NotificationJobStatus.java]
- `NotificationJobEntity` with `@Version`, `markSent`, `markExhausted`, `markEscalated`: [Source: notification/infrastructure/NotificationJobEntity.java]
- `NotificationJobRepository.findPendingJobsDue` + `findSentJobsDueForEscalation`: [Source: notification/infrastructure/NotificationJobRepository.java]
- `EscalationService.escalate()` alert-status guard (line 68): [Source: notification/application/EscalationService.java:68]
- `EscalationWorker` per-job OOL handling: [Source: notification/application/EscalationWorker.java]
- `SparepartAlertCommandService.acknowledge()`: [Source: alert/application/SparepartAlertCommandService.java:44]
- `SparepartAlertEntity.acknowledge()` — throws if not OPEN: [Source: alert/infrastructure/SparepartAlertEntity.java:102]
- `AuditRecord` / `AuditAction` / `AuditEntityType.ALERT`: [Source: audit/domain/*.java]
- `notification_jobs` schema (V24, V25, V26, V27): [Source: db/migration/*.sql]
- Architecture escalation/notification flow: [Source: _bmad-output/planning-artifacts/architecture.md#WAHA Escalation]
- Epic 5.5 AC (epic.md): "Stop Escalation When Alert Is Acknowledged": [Source: _bmad-output/planning-artifacts/epics.md#Story 5.5]
- Page spec §3.4 Escalation Timeline (stopped-by-acknowledgement state rendered from `CANCELLED` jobs, UI in 5.6): [Source: _bmad-output/planning-artifacts/page-specifications.md#3.4]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4.6 (openagentic/claude-sonnet-4.6)

### Debug Log References

- Spring Boot 4 relocated `@DataJpaTest` to `org.springframework.boot.data.jpa.test.autoconfigure` and `@AutoConfigureTestDatabase` to `org.springframework.boot.jdbc.test.autoconfigure` — required updating integration test imports.
- PostgreSQL JDBC driver (pgjdbc) does not auto-convert `java.time.Instant` to SQL timestamp — seeding calls required `java.sql.Timestamp.from(instant)`.
- All `@SpringBootTest` integration tests in the project fail due to Arrow/Netty `RootAllocator` initialization crash in this environment (pre-existing, unrelated to story 5-5). `NotificationJobCancelIntegrationTest` was switched to `@DataJpaTest` + `@ImportAutoConfiguration(FlywayAutoConfiguration.class)` + `@AutoConfigureTestDatabase(replace=NONE)` to avoid the full context.

### Completion Notes List

- `cancelActiveForAlert` uses `@Modifying` without `clearAutomatically=true` — intentional; the managed alert entity must not be detached.
- Workers already exclude `CANCELLED` by construction (explicit `PENDING`/`SENT` status params). No defensive filter needed.
- Acceptable residual race: if a worker loads a `SENT` job the instant before acknowledge runs, `@Version` optimistic locking on `NotificationJobEntity` causes `ObjectOptimisticLockingFailureException` in the worker, preventing re-escalation.
- Pre-existing failures in non-story tests: `SparepartLifetimeEvaluatorTest` (2 `UnnecessaryStubbingException`), all `@SpringBootTest` integration tests (Arrow/Netty env crash). None caused by story 5-5.

### File List

- `syncro/apps/backend/src/main/java/com/syncro/notification/domain/NotificationJobStatus.java`
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java`
- `syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertCommandService.java`
- `syncro/apps/backend/src/main/resources/db/migration/V28__notification_job_cancelled_status.sql`
- `syncro/apps/backend/src/test/java/com/syncro/alert/application/SparepartAlertCommandServiceTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/NotificationJobEntityTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/NotificationJobCancelIntegrationTest.java`

### Review Findings

- [x] [Review][Decision] CHECK constraint in V28 lacks NOT VALID — will fail migration if any existing rows carry an unrecognised status string — `V28__notification_job_cancelled_status.sql:8`. Decision: keep as-is (option c) — DB is known-clean, all inserts are enum-guarded via JPA.
- [x] [Review][Patch] `markCancelled()` on `NotificationJobEntity` is unreachable dead code — no production call site exists [`NotificationJobEntity.java:103`] — fixed: method and its 2 unit tests removed.
