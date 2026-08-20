---
title: 'Stop Escalation When Alert Is Acknowledged'
type: 'feature'
created: '2026-08-20'
status: 'ready-for-dev'
baseline_commit: '8928289'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/spec-5-2-queue-initial-waha-notification-for-open-alert.md'
  - '_bmad-output/implementation-artifacts/spec-5-3-send-notification-job-through-waha-with-attempt-history.md'
  - '_bmad-output/implementation-artifacts/spec-5-4-escalate-alert-notifications-by-responsibility-level.md'
warnings: []
---

# Story 5.5: Stop Escalation When Alert Is Acknowledged

Status: ready-for-dev

## Story

As a system,
I want to cancel all active notification jobs when an alert is acknowledged,
so that escalation stops and no further WhatsApp messages are sent after a human responds.

## Acceptance Criteria

1. When an alert transitions from `OPEN` to `ACKNOWLEDGED`, all `notification_jobs` rows for that alert with `status IN (PENDING, SENT)` are bulk-updated to `status=CANCELLED` within the same database transaction as the acknowledgement.
2. The cancellation is triggered by an `AlertAcknowledgedEvent` published by `SparepartAlertCommandService.acknowledge()` via Spring's `ApplicationEventPublisher`, and consumed by a new `@TransactionalEventListener(phase = AFTER_COMMIT)` in the notification package — following the exact same pattern as `AlertOpenedEvent` / `NotificationRoutingService`.
3. The `NotificationJobStatus` enum gains a new value `CANCELLED`.
4. `NotificationJobEntity` gains a new mutator `markCancelled(Instant now)` — sets `status = CANCELLED`, `updatedAt = now` — consistent with the existing `markEscalated`, `markSent`, `markExhausted` pattern.
5. `NotificationJobRepository` gains a new JPQL bulk-update method `cancelActiveJobsForAlert(UUID alertId, Instant now)` that sets `status = CANCELLED` and `updatedAt = now` for all rows where `alertId = :alertId AND status IN ('PENDING', 'SENT')`. The method is annotated `@Modifying @Transactional @Query(...)`.
6. Jobs with terminal statuses (`ESCALATED`, `EXHAUSTED`, `ROUTING_FAILED`, `CANCELLED`) are NOT affected — only `PENDING` and `SENT` are cancelled.
7. If no active jobs exist for the alert at acknowledgement time, the operation is a no-op (zero rows updated — not an error).
8. A new Flyway migration `V28__add_cancelled_notification_status_index.sql` adds an index on `notification_jobs(alert_id, status)` to support the bulk-cancel query efficiently.
9. The `EscalationWorker` and `NotificationWorker` already skip `CANCELLED` jobs implicitly because their queries filter by specific statuses (`SENT` and `PENDING` respectively) — no changes needed to those workers.
10. Unit tests cover: happy-path bulk cancel (2 active jobs → both CANCELLED), no-op (zero active jobs), only terminal-status jobs present (none cancelled), and the event listener wiring.

## Tasks / Subtasks

- [ ] Task 1: Add `AlertAcknowledgedEvent` domain event (AC: 2)
  - [ ] Create `com.syncro.notification.domain.AlertAcknowledgedEvent` as a plain record: `public record AlertAcknowledgedEvent(UUID alertId) {}`
  - [ ] Place in `notification.domain` package alongside `AlertOpenedEvent` — check the existing event's location first and match it exactly

- [ ] Task 2: Publish event in `SparepartAlertCommandService.acknowledge()` (AC: 1, 2)
  - [ ] Inject `ApplicationEventPublisher` into `SparepartAlertCommandService` via constructor
  - [ ] After `alertRepository.save(alert)` (and before/after auditLogWriter call — order doesn't matter since event is AFTER_COMMIT), call `eventPublisher.publishEvent(new AlertAcknowledgedEvent(alertId))`
  - [ ] Do NOT change the existing acknowledge() transaction boundary — the event listener runs AFTER_COMMIT, so the alert save is already committed when jobs are cancelled

- [ ] Task 3: Add `CANCELLED` to `NotificationJobStatus` enum (AC: 3)
  - [ ] Append `CANCELLED` to `com.syncro.notification.domain.NotificationJobStatus`

- [ ] Task 4: Add `markCancelled()` mutator to `NotificationJobEntity` (AC: 4)
  - [ ] Add `public void markCancelled(Instant now)` — sets `this.status = NotificationJobStatus.CANCELLED; this.updatedAt = now;`
  - [ ] Place after `markEscalated(Instant now)` to maintain grouping with other mutators

- [ ] Task 5: Add bulk-cancel query to `NotificationJobRepository` (AC: 5, 6, 7)
  - [ ] Add method `cancelActiveJobsForAlert(@Param("alertId") UUID alertId, @Param("now") Instant now)`
  - [ ] JPQL: `UPDATE NotificationJobEntity j SET j.status = com.syncro.notification.domain.NotificationJobStatus.CANCELLED, j.updatedAt = :now WHERE j.alertId = :alertId AND j.status IN (com.syncro.notification.domain.NotificationJobStatus.PENDING, com.syncro.notification.domain.NotificationJobStatus.SENT)`
  - [ ] Annotate with `@Modifying`, `@Transactional`, `@Query(...)`
  - [ ] Return type `int` (number of rows updated)

- [ ] Task 6: Create `NotificationCancellationService` (AC: 1, 2, 6, 7)
  - [ ] Create `com.syncro.notification.application.NotificationCancellationService`
  - [ ] Annotate `@Service`
  - [ ] Inject `NotificationJobRepository`, `Clock`
  - [ ] Method `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` `onAlertAcknowledged(AlertAcknowledgedEvent event)`:
    - Call `notificationJobRepository.cancelActiveJobsForAlert(event.alertId(), Instant.now(clock))`
    - Log INFO: `[NotificationCancellationService] Cancelled {} active jobs for alert {}` with count and alertId
  - [ ] Follow exact same structure as `NotificationRoutingService.onAlertOpened()` — same imports, same annotation style

- [ ] Task 7: Add Flyway migration `V28__add_cancelled_notification_status_index.sql` (AC: 8)
  - [ ] Create `syncro/apps/backend/src/main/resources/db/migration/V28__add_cancelled_notification_status_index.sql`
  - [ ] Content: `CREATE INDEX IF NOT EXISTS idx_notification_jobs_alert_id_status ON notification_jobs (alert_id, status);`
  - [ ] This index supports both the bulk-cancel query (alert_id + status filter) and future history queries by alert

- [ ] Task 8: Unit tests (AC: 10)
  - [ ] Create `com.syncro.notification.application.NotificationCancellationServiceTest`
  - [ ] Test 1: `onAlertAcknowledged_cancelsActiveJobs` — mock repo returns 2 (two rows updated), verify `cancelActiveJobsForAlert` called with correct alertId and a non-null Instant
  - [ ] Test 2: `onAlertAcknowledged_whenNoActiveJobs_isNoOp` — mock repo returns 0, verify no exception thrown, method completes normally
  - [ ] Use `@ExtendWith(MockitoExtension.class)`, `Clock.fixed(...)` injected via constructor, `ReflectionTestUtils` not needed (use constructor injection)
  - [ ] All tests must pass with `mvnw test -Dtest="NotificationCancellationServiceTest"`

## Dev Notes

### Context: What Stories 5.1–5.4 Built

**Story 5.2** introduced:
- `notification_jobs` table (V24) and `NotificationRoutingService`
- `AlertOpenedEvent` published by alert creation flow
- `@TransactionalEventListener(phase = AFTER_COMMIT)` pattern for event → notification wiring
- Idempotency key pattern: `alertId + "::" + levelName`

**Story 5.3** introduced:
- `notification_attempts` table (V25), `@Version` optimistic locking (V26)
- `NotificationWorker` polls `PENDING` jobs (filters `status = :status`)
- `SENT` / `EXHAUSTED` status transitions
- `sentAt`, `attemptCount`, `nextAttemptAt`, `maxAttempts` on entity

**Story 5.4** introduced:
- `EscalationWorker` polls `SENT` jobs (filters `status = 'SENT'`)
- `ESCALATED` status and `markEscalated()` mutator
- `EscalationService` — skips escalation if `alert.status != OPEN` (AC10 of 5.4)
- V27 index on `(status, sent_at)`

**This story (5.5)** adds the stop-escalation trigger: when a user acknowledges an alert, all `PENDING` and `SENT` jobs for that alert must be cancelled **immediately** — not on the next worker poll cycle.

### Critical Pattern: AFTER_COMMIT Event Listener

The AFTER_COMMIT pattern is essential. The acknowledgement transaction (alert → ACKNOWLEDGED) must commit first, then the cancellation runs. This prevents the escalation worker from seeing a stale `OPEN` alert status during the cancel window.

Existing reference: `NotificationRoutingService.java` — read this file before implementing `NotificationCancellationService`. Mirror the imports, annotation, and structure exactly.

### Why Bulk Update (Not Entity-Level)

The `cancelActiveJobsForAlert` uses a JPQL bulk UPDATE rather than loading each entity and calling `markCancelled()`. This is correct for this use case:
- Number of active jobs per alert can be up to 5 (one per escalation level)
- Bulk update avoids N+1 loads and bypasses optimistic lock conflicts (version column is not incremented by bulk UPDATE in JPA)
- The `@Modifying` annotation clears the persistence context after execution

**Note:** If any job was being processed by `EscalationWorker` or `NotificationWorker` concurrently, the worker's eventual `save(job)` will update the row again — but that's acceptable because the worker will be a no-op (EscalationService already checks `alert.status != OPEN`).

### AlertAcknowledgedEvent Location

Check where `AlertOpenedEvent` lives:
```
com.syncro.notification.domain.AlertOpenedEvent
```
Place `AlertAcknowledgedEvent` in the same package. Do not put it in the `alert` domain package — events are owned by the consuming context (`notification`), not the publishing context (`alert`). This is the existing pattern.

### NotificationJobRepository — Existing Queries

Current queries as of baseline commit `8928289`:
- `findPendingJobsDue(NotificationJobStatus status, Instant now)` — used by `NotificationWorker`
- `findSentJobsDueForEscalation(NotificationJobStatus status, Instant cutoff)` — used by `EscalationWorker`

The new `cancelActiveJobsForAlert` is additive — no changes to existing queries.

### Files to Touch

**New files:**
- `syncro/apps/backend/src/main/java/com/syncro/notification/domain/AlertAcknowledgedEvent.java`
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationCancellationService.java`
- `syncro/apps/backend/src/main/resources/db/migration/V28__add_cancelled_notification_status_index.sql`
- `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationCancellationServiceTest.java`

**Modified files:**
- `syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertCommandService.java` (inject publisher, publish event)
- `syncro/apps/backend/src/main/java/com/syncro/notification/domain/NotificationJobStatus.java` (add CANCELLED)
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobEntity.java` (add markCancelled)
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` (add cancelActiveJobsForAlert)

### Project Structure Notes

- Package: `com.syncro.notification.application` for service, `com.syncro.notification.domain` for event and enum
- All Spring-managed versions via BOM in `pom.xml` — do not pin Spring dependencies
- Jakarta namespace only (`jakarta.persistence.*`, not `javax.*`)
- `Clock` bean is already wired in the application context — inject via constructor, no `@Value` needed
- Flyway migration must be `V28__` (V27 is `add_escalation_polling_index.sql`)
- Run tests with: `$env:JAVA_HOME = "C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"; & "E:\01 DEV\SYNCRO-SPRING\mvnw.cmd" test -Dtest="NotificationCancellationServiceTest" --no-transfer-progress` from `syncro/apps/backend`

### References

- Existing event pattern: `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationRoutingService.java`
- Existing AlertOpenedEvent: `syncro/apps/backend/src/main/java/com/syncro/notification/domain/AlertOpenedEvent.java` (verify location)
- Acknowledgement entry point: `syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertCommandService.java:45` — `acknowledge()` method
- Existing mutator pattern: `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobEntity.java` — `markEscalated()`, `markSent()`, `markExhausted()`
- Existing bulk-update example: check if any existing repo uses `@Modifying @Query` — if not, follow standard Spring Data JPA docs for JPQL bulk UPDATE
- FR-051: `_bmad-output/planning-artifacts/epics.md` — "If an alert is acknowledged, the system shall stop further escalation for that alert."

## Dev Agent Record

### Agent Model Used

kiro

### Debug Log References

### Completion Notes List

### File List
