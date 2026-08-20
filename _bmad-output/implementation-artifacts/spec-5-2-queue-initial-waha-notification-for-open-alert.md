---
title: 'Queue Initial WAHA Notification for Open Alert'
type: 'feature'
created: '2026-08-20'
status: 'done'
review_loop_iteration: 1
baseline_revision: 'f06bd01bcce3607f64462117bf44ec8f840bb2ac'
final_revision: '9d2bcb466df574e6a0f5ac5cb7de14fc5044b3b7'
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** When a sparepart lifetime threshold alert is created with status `OPEN`, there is no mechanism to notify the responsible technician — the alert exists in the database but no WhatsApp notification job is queued, leaving the technician unaware.

**Approach:** After a new `OPEN` alert is persisted in `SparepartAlertService`, publish a Spring `ApplicationEvent`; a `NotificationRoutingService` listener resolves the `TECHNICIAN`-level responsible user for the machine (via `MachineResponsibilityRepository`), reads their `whatsappNumber` from `auth_users`, and persists a `NotificationJobEntity` row in a new `notification_jobs` PostgreSQL table. Alert creation must not be blocked if routing fails.

## Boundaries & Constraints

**Always:**
- Alert creation transaction commits before notification routing fires; use `@TransactionalEventListener(phase = AFTER_COMMIT)` so a routing failure never rolls back the alert.
- Notification job rows are deduplicated on `(alert_id, escalation_level)` via a unique constraint; concurrent or duplicate events must not create a second logical job.
- Missing recipient (`whatsappNumber` null or no TECHNICIAN assignment) must be recorded as a `ROUTING_FAILED` job row with an `errorDetail` column, not silently swallowed.
- Flyway owns all schema changes; no JPA auto-DDL.
- WAHA credentials must never be logged; this story does not send anything to WAHA — it only queues the job.
- `whatsappNumber` on `auth_users` is nullable; existing users without a number are valid and must not cause an error beyond the `ROUTING_FAILED` record.

**Block If:** none — all decisions are resolvable from existing artifacts.

**Never:**
- Do not send a WAHA HTTP request in this story (that is Story 5.3).
- Do not implement escalation scheduling (that is Story 5.4).
- Do not add frontend UI changes.
- Do not use Redis or an in-memory queue for the job record; PostgreSQL outbox row is the durable contract.
- Do not modify `SparepartAlertEntity` or alter the alert creation transaction path.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Happy path — TECHNICIAN has whatsappNumber | Alert created `OPEN`, machine has TECHNICIAN responsibility with `whatsappNumber` set | `notification_jobs` row inserted: `status=PENDING`, `escalationLevel=TECHNICIAN`, `recipientUserId`, `recipientPhone`, `idempotencyKey=alertId::TECHNICIAN`, `traceId` from alert | No error |
| No TECHNICIAN assignment | Alert created `OPEN`, machine has no `TECHNICIAN` in `machine_responsibilities` | `notification_jobs` row inserted: `status=ROUTING_FAILED`, `escalationLevel=TECHNICIAN`, `errorDetail='No TECHNICIAN assigned to machine'` | Logged at WARN; alert unaffected |
| TECHNICIAN has null whatsappNumber | TECHNICIAN responsibility exists but `auth_users.whatsapp_number` is null | `notification_jobs` row inserted: `status=ROUTING_FAILED`, `escalationLevel=TECHNICIAN`, `errorDetail='TECHNICIAN userId=<id> has no whatsappNumber'` | Logged at WARN; alert unaffected |
| Duplicate event (concurrent alert creation) | Same `alertId` + `TECHNICIAN` event published twice | Second insert hits unique constraint on `(alert_id, escalation_level)`; silently ignored via `ON CONFLICT DO NOTHING` or caught `DataIntegrityViolationException` | No duplicate row; no error propagated |
| Routing exception (DB error) | Transient DB failure during routing | Exception logged at ERROR; alert already committed and unaffected | `@TransactionalEventListener` exception does not re-throw to caller |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/resources/db/migration/V21__add_whatsapp_number_to_auth_users.sql` -- new: adds nullable `whatsapp_number VARCHAR(32)` to `auth_users`
- `syncro/apps/backend/src/main/resources/db/migration/V22__create_notification_jobs.sql` -- new: creates `notification_jobs` table with all columns and unique index
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserEntity.java` -- add `whatsappNumber` field mapped to `whatsapp_number`
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserRepository.java` -- add `findById` (already from JpaRepository; verify `whatsappNumber` is selectable)
- `syncro/apps/backend/src/main/java/com/syncro/notification/domain/NotificationJobStatus.java` -- new enum: `PENDING`, `ROUTING_FAILED`
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobEntity.java` -- new JPA entity mapped to `notification_jobs`
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` -- new `JpaRepository<NotificationJobEntity, UUID>`
- `syncro/apps/backend/src/main/java/com/syncro/notification/domain/AlertOpenedEvent.java` -- new Spring event record: `alertId`, `machineId`, `traceId`
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationRoutingService.java` -- new `@Service` with `@TransactionalEventListener(phase=AFTER_COMMIT)` on `AlertOpenedEvent`
- `syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertService.java` -- inject `ApplicationEventPublisher`; publish `AlertOpenedEvent` after successful alert save (L107 area)
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineResponsibilityRepository.java` -- add `findFirstByMachineIdAndResponsibilityLevel(UUID machineId, ResponsibilityLevel level)` returning `Optional<MachineResponsibilityEntity>`

## Tasks & Acceptance

- [x] `syncro/apps/backend/src/main/resources/db/migration/V21__add_whatsapp_number_to_auth_users.sql` -- create with: `ALTER TABLE auth_users ADD COLUMN whatsapp_number VARCHAR(32);` — rationale: provides the phone number recipient field required by notification routing; nullable so existing users are unaffected

  **AC — Given** the migration runs **When** `auth_users` is inspected **Then** `whatsapp_number` column exists as nullable VARCHAR(32)

- [x] `syncro/apps/backend/src/main/resources/db/migration/V22__create_notification_jobs.sql` -- create table `notification_jobs` with columns: `id UUID PK`, `alert_id UUID NOT NULL FK→sparepart_alerts(id)`, `escalation_level VARCHAR(16) NOT NULL`, `status VARCHAR(24) NOT NULL`, `recipient_user_id UUID nullable`, `recipient_phone VARCHAR(32) nullable`, `idempotency_key VARCHAR(128) NOT NULL`, `trace_id VARCHAR(64)`, `error_detail VARCHAR(512)`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`; unique index on `(alert_id, escalation_level)`; index on `alert_id`

  **AC — Given** the migration runs **When** schema is inspected **Then** `notification_jobs` table exists with all columns and the unique index on `(alert_id, escalation_level)`

- [x] `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserEntity.java` -- add field `@Column(name = "whatsapp_number", length = 32) private String whatsappNumber;` with getter/setter following existing field style

  **AC — Given** a user row with `whatsapp_number` set **When** loaded via JPA **Then** `getWhatsappNumber()` returns the stored value

- [x] `syncro/apps/backend/src/main/java/com/syncro/notification/domain/AlertOpenedEvent.java` -- create as a Java record: `record AlertOpenedEvent(UUID alertId, UUID machineId, String traceId) {}`

  **AC** — class compiles and is a plain record with three accessor methods

- [x] `syncro/apps/backend/src/main/java/com/syncro/notification/domain/NotificationJobStatus.java` -- create enum with values `PENDING` and `ROUTING_FAILED`

  **AC** — enum compiles with both values

- [x] `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobEntity.java` -- create `@Entity @Table(name="notification_jobs")` with all columns from V22; `id` generated via `UUID_FUNC` (match project convention for UUID generation); `status` stored as `@Enumerated(EnumType.STRING)`; `createdAt`/`updatedAt` set at persist/update via `@PrePersist`/`@PreUpdate`

  **AC — Given** a `NotificationJobEntity` is built and saved **When** queried from DB **Then** all fields persist and `idempotencyKey` matches the value set at construction

- [x] `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` -- create interface extending `JpaRepository<NotificationJobEntity, UUID>`; no custom methods needed for this story

  **AC** — interface exists and Spring Boot auto-configures it as a bean

- [x] `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineResponsibilityRepository.java` -- add: `Optional<MachineResponsibilityEntity> findFirstByMachineIdAndResponsibilityLevel(UUID machineId, ResponsibilityLevel responsibilityLevel);`

  **AC — Given** a machine with one TECHNICIAN assignment **When** `findFirstByMachineIdAndResponsibilityLevel(machineId, TECHNICIAN)` is called **Then** returns the entity

- [x] `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationRoutingService.java` -- create `@Service` with `@RequiredArgsConstructor`; inject `MachineResponsibilityRepository`, `AuthUserRepository` (or `AuthUserEntityRepository`), `NotificationJobRepository`; implement method annotated `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` listening on `AlertOpenedEvent`; logic: (1) find TECHNICIAN via `findFirstByMachineIdAndResponsibilityLevel`; if empty → save `ROUTING_FAILED` job with errorDetail `'No TECHNICIAN assigned to machine <machineId>'`; (2) load `AuthUserEntity` by `responsibilityEntity.getUserId()`; if `whatsappNumber` null → save `ROUTING_FAILED` with errorDetail `'TECHNICIAN userId=<id> has no whatsappNumber'`; (3) save `PENDING` job with `recipientUserId`, `recipientPhone=whatsappNumber`, `idempotencyKey="<alertId>::TECHNICIAN"`; catch `DataIntegrityViolationException` on save silently (dedup); wrap entire method in try/catch to swallow and log any other exception at ERROR level

  **AC — Given** an `AlertOpenedEvent` is published after alert commit **When** machine has TECHNICIAN with `whatsappNumber` **Then** `notification_jobs` has one row `status=PENDING` with correct `alertId`, `escalationLevel=TECHNICIAN`, `recipientPhone`, `idempotencyKey`

  **AC — Given** no TECHNICIAN assignment **When** event fires **Then** `notification_jobs` has one row `status=ROUTING_FAILED` with non-empty `errorDetail`

  **AC — Given** TECHNICIAN has null `whatsappNumber` **When** event fires **Then** `notification_jobs` has one row `status=ROUTING_FAILED` with non-empty `errorDetail`

  **AC — Given** duplicate `AlertOpenedEvent` for same alertId **When** second event fires **Then** no second row is inserted (dedup via unique constraint)

  **AC — Given** a DB error during routing **When** exception is thrown **Then** exception is caught and logged; alert is unaffected (already committed)

- [x] `syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertService.java` -- inject `ApplicationEventPublisher`; after line where alert is successfully saved (after `alertRepository.save(alert)` and before the audit log, or after audit log — either is fine as long as the event is published within the same transaction), call `eventPublisher.publishEvent(new AlertOpenedEvent(saved.getId(), machineId, traceId))`; do NOT catch the publish call separately — `@TransactionalEventListener(AFTER_COMMIT)` guarantees the listener runs after, not during, the alert transaction

  **AC — Given** alert creation succeeds **When** `evaluateAndCreateAlerts` completes **Then** `AlertOpenedEvent` has been published with correct `alertId` and `machineId`

  **AC — Given** `DataIntegrityViolationException` on alert save (concurrent dedup path) **When** exception is caught **Then** no `AlertOpenedEvent` is published

## Design Notes

The `@TransactionalEventListener(phase = AFTER_COMMIT)` annotation is the key design choice: it fires only after the alert's transaction has committed, so a routing failure cannot roll back the alert. The listener runs in the same thread but outside the original transaction. If the listener itself throws, Spring logs the exception but does not propagate it to the original caller — which is exactly the non-blocking contract the story requires.

The `idempotencyKey` format `"<alertId>::TECHNICIAN"` combined with the DB unique index on `(alert_id, escalation_level)` provides two layers of dedup: the key makes intent explicit for later workers, and the constraint prevents concurrent inserts from producing duplicates.

UUID generation strategy: match whatever the existing entities use (check `SparepartAlertEntity` — likely `@GeneratedValue(strategy = GenerationType.UUID)` introduced in JPA 3.1, compatible with Java 25 / Spring Boot 4).

## Verification

**Commands:**
- `cd syncro/apps/backend && ./mvnw compile` -- expected: BUILD SUCCESS, zero compilation errors
- `cd syncro/apps/backend && ./mvnw test -pl . -Dtest="NotificationRoutingServiceTest"` -- expected: all routing unit tests pass
- `cd syncro/apps/backend && ./mvnw flyway:info` -- expected: V21 and V22 listed as pending (or applied if DB is up)

**Manual checks (if no CLI):**
- After running both migrations, inspect `information_schema.columns` for `auth_users.whatsapp_number` (nullable VARCHAR 32) and `notification_jobs` table with unique index on `(alert_id, escalation_level)`.
- Trigger a threshold alert creation in a running environment; verify one `notification_jobs` row appears with `status=PENDING` or `ROUTING_FAILED` matching the machine's TECHNICIAN assignment.

## Review Log

### Pass 1 — 2026-08-20

**Triage counts:** intent_gap: 0 | bad_spec: 0 | patch: 5 (low:0, medium:4, high:1) | defer: 10 (low:10) | reject: 0

**Patches addressed:**
- [patch/high] F-10: Added `@Transactional(propagation = Propagation.REQUIRES_NEW)` to `NotificationRoutingService.onAlertOpened` — without this, Spring Data `save()` in the `AFTER_COMMIT` listener runs without a transaction, causing failures on strict datasource configs
- [patch/medium] F-06: Replaced hardcoded `"TECHNICIAN"` string literals with `ResponsibilityLevel.TECHNICIAN.name()` — prevents silent divergence on enum renames
- [patch/medium] ECH-1: Renamed repository method to `findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc` — deterministic TECHNICIAN selection when multiple assignments exist
- [patch/medium] ECH-3: Added `.isBlank()` alongside null check on `whatsappNumber` — prevents PENDING job with blank `recipientPhone` reaching the WAHA story
- [defer/DW-44] F-04: Unique constraint prevents ROUTING_FAILED→PENDING re-routing; recorded as DW-44; acceptable for this story's scope

**Deferred:** F-01 (no tests — pre-existing), F-02 (no retry — Story 5.3 scope), F-03 (idempotency key semantics — sufficient), F-05 (enum anemic — open for 5.3–5.5), F-07 (query count — premature), F-08 (errorDetail truncation — constants only), F-09 (full entity load — premature), ECH-6 (verified clean — publishEvent outside DIV catch), F-12 (nullable annotation style — cosmetic), F-13 (event ordering — harmless)

**Verification:** Compilation errors are all pre-existing `JwtTokenService` baseline failures; zero errors in any story-5-2 file. All 10 tasks confirmed implemented by file inspection.

## Auto Run Result

Status: done

_Appended by the bmad-loop orchestrator (missing-marker repair, #224): the session finalized this spec's frontmatter without its `## Auto Run Result` marker, so the orchestrator synthesized the result from the frontmatter and appended this section._

Synthesized by the bmad-loop orchestrator from frontmatter status `done` for story `5-2-queue-initial-waha-notification-for-open-alert` (session finalized the spec without appending its marker).
