---
title: 'Create Threshold Alert with Duplicate Prevention'
type: 'feature'
created: '2026-08-19'
status: 'in-review'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: '7842ee60ded49d07be25e8b12edc4f51f75ee0bd'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-4-context.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** `TelemetryPersistenceService` calls `evaluator.evaluateAll()` after each telemetry persist but discards the result — no alert is ever created when a sparepart's consumed percentage reaches or exceeds its threshold, so maintenance operators have no automated signal of sparepart lifetime risk.

**Approach:** Introduce an `alert` package with `SparepartAlertEntity`, `SparepartAlertRepository`, and `SparepartAlertService`. Wire `SparepartAlertService` into `TelemetryPersistenceService` to consume the `evaluateAll` result, check the dedup constraint `(installationId, thresholdPercentage, non-RESOLVED)`, and create one `OPEN` alert per threshold crossing. Add `ALERT` to `AuditEntityType`, add `recordSystem()` overload to `AuditLogWriter` for system-triggered audit events, and write a Flyway migration for the `sparepart_alerts` table.

## Boundaries & Constraints

**Always:**
- Dedup key is `(machineSparepartInstallationId, thresholdPercentage)` scoped to non-RESOLVED alerts. Only one non-resolved alert per installation+threshold is allowed at any time.
- Alert entity records: `machineId`, `machineSparepartInstallationId`, `thresholdPercentage`, `currentCounterSnapshot` (at firing), `consumedProductionCountSnapshot` (at firing), `consumedPercentageSnapshot` (at firing), `traceId`, `status` (`OPEN`), `statusReason`, `createdAt`, `updatedAt`.
- Alert status enum `SparepartAlertStatus`: `OPEN`, `ACKNOWLEDGED`, `RESOLVED`. Backend-owned; frontend never sets or derives status independently.
- Alert creation always writes an audit event via `AuditLogWriter.recordSystem()` with `AuditEntityType.ALERT` and `AuditAction.CREATE`.
- `AuditLogWriter.recordSystem()` uses `actorId = UUID(0,0)` (all-zeros UUID) and `actorName = "SYSTEM"`.
- Alert creation happens inside a `@Transactional` method in `SparepartAlertService`; transaction boundary is this service, not `TelemetryPersistenceService`.
- `TelemetryPersistenceService.persist()` calls `alertService.evaluateAndCreateAlerts(machineId, evaluationResults, traceId)` wrapped in `try/catch(Exception)` — alert failure must never fail telemetry persistence.
- Flyway migration version is `V10` (last existing is `V9__create_telemetry_quarantine.sql`).
- Use `BIGINT` for counter snapshot columns in the migration (matches Java `long`).
- `NUMERIC(7,2)` for `consumed_percentage_snapshot` (matches `BigDecimal` scale 2, max 999.99%).

**Block If:**
- Schema of `audit_log` table has `actor_id NOT NULL` constraint that cannot accept `UUID(0,0)` — verify before writing migration (existing schema already has this as `NOT NULL`; `UUID(0,0)` is a valid UUID value so this should pass, but block if DB rejects it).

**Never:**
- Do not implement alert lifecycle transitions (ACKNOWLEDGED, RESOLVED) — those are Stories 4.4–4.6.
- Do not expose any REST endpoint for alerts — that is Story 4.3.
- Do not recalculate consumed percentage inside `SparepartAlertService` — use `EvaluationResult` values passed in from `evaluator.evaluateAll()`.
- Do not call `evaluator.evaluateAll()` a second time inside `SparepartAlertService`.
- Do not add `@Transactional` to `TelemetryPersistenceService.persist()` — it is already transactionless by design.
- Do not modify `AuditAction` enum — `CREATE` is sufficient.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Threshold reached, no existing alert | `consumedPercentage >= thresholdPercentage`, no non-RESOLVED alert for installation+threshold | New `SparepartAlertEntity` saved with status `OPEN`, audit event written | No error expected |
| Duplicate telemetry at same threshold | Second telemetry message, existing `OPEN` alert for same installation+threshold | No new alert created (dedup check finds existing non-RESOLVED) | No error expected |
| Below threshold | `consumedPercentage < thresholdPercentage` | No alert created | No error expected |
| Redis miss — no evaluation result | `evaluateAll()` returns empty map | No alert created, no error | No error expected |
| Alert service throws | Any exception in `evaluateAndCreateAlerts()` | Exception caught in `TelemetryPersistenceService`, logged at WARN with traceId, telemetry persist continues | Must not fail telemetry |
| Existing RESOLVED alert, threshold re-crossed | `consumedPercentage >= thresholdPercentage`, only RESOLVED alert exists | New `OPEN` alert created (RESOLVED is excluded from dedup check) | No error expected |
| `expectedProductionCount == 0` | Evaluator already skips this case | No result in map, no alert created | No error expected |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartLifetimeEvaluator.java` -- `EvaluationResult` record; `evaluateAll(UUID machineId)` returns `Map<UUID, EvaluationResult>` keyed by installationId
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` -- calls `evaluator.evaluateAll(machineId)` at line 95, result currently discarded; wire alert service here
- `syncro/apps/backend/src/main/java/com/syncro/audit/application/AuditLogWriter.java` -- `record(AuthenticatedUser, AuditRecord)` at line 28; needs `recordSystem(AuditRecord)` overload
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- enum; add `ALERT`
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditAction.java` -- enum `CREATE`, `UPDATE`, `DELETE`; no change needed
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/MachineSparepartInstallationEntity.java` -- `thresholdPercentage` (int), `id` (UUID), `machine` (MachineEntity with `getId()`)
- `syncro/apps/backend/src/main/resources/db/migration/` -- last migration is `V9__create_telemetry_quarantine.sql`; new file is `V10__create_sparepart_alerts.sql`

## Tasks & Acceptance

- [x] 1. `syncro/apps/backend/src/main/resources/db/migration/V19__create_sparepart_alerts.sql` -- created (V10-V18 already existed; used V19 as next available)

- [x] 2. `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- added `ALERT` to enum

- [x] 3. `syncro/apps/backend/src/main/java/com/syncro/audit/application/AuditLogWriter.java` -- added `recordSystem(AuditRecord record)` overload with `actorId = new UUID(0L, 0L)` and `actorName = "SYSTEM"`

- [x] 4. `syncro/apps/backend/src/main/java/com/syncro/alert/domain/SparepartAlertStatus.java` -- new enum `OPEN`, `ACKNOWLEDGED`, `RESOLVED`

- [x] 5. `syncro/apps/backend/src/main/java/com/syncro/alert/infrastructure/SparepartAlertEntity.java` -- new `@Entity @Table("sparepart_alerts")`

- [x] 6. `syncro/apps/backend/src/main/java/com/syncro/alert/infrastructure/SparepartAlertRepository.java` -- new `JpaRepository` with dedup check method

- [x] 7. `syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertService.java` -- new `@Service` with `evaluateAndCreateAlerts()`

- [x] 8. `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` -- injected `SparepartAlertService`, wired evaluator results to alert creation

- [x] 9. `syncro/apps/backend/src/test/java/com/syncro/alert/application/SparepartAlertServiceTest.java` -- 5/5 unit tests pass

## Acceptance Criteria

**Given** accepted telemetry causes `evaluateAll` to return a result where `consumedPercentage >= thresholdPercentage`
**When** `evaluateAndCreateAlerts` runs
**Then** backend creates a `SparepartAlertEntity` with status `OPEN`
**And** entity records `machineId`, `machineSparepartInstallationId`, `thresholdPercentage`, `currentCounterSnapshot`, `consumedProductionCountSnapshot`, `consumedPercentageSnapshot`, and `traceId`
**And** audit event is written with `AuditEntityType.ALERT`, `AuditAction.CREATE`, `actorName = "SYSTEM"`

**Given** a non-RESOLVED alert already exists for the same `(installationId, thresholdPercentage)`
**When** `evaluateAndCreateAlerts` runs again for the same installation
**Then** no new alert is created

**Given** a RESOLVED alert exists for the same `(installationId, thresholdPercentage)` and threshold is re-crossed
**When** `evaluateAndCreateAlerts` runs
**Then** a new `OPEN` alert is created

**Given** `evaluateAndCreateAlerts` throws any exception
**When** called from `TelemetryPersistenceService.persist()`
**Then** telemetry persistence completes successfully and exception is logged at WARN with traceId

**Given** `consumedPercentage < thresholdPercentage`
**When** `evaluateAndCreateAlerts` runs
**Then** no alert is created

## Residual Risks

- Partial index `WHERE status != 'RESOLVED'` syntax is PostgreSQL-specific — acceptable given project uses PostgreSQL exclusively.
- `UUID(0,0)` as SYSTEM actor is a sentinel value; if future queries join on `actor_id` expecting a real user, they will find no match. This is intentional and documented by `actorName = "SYSTEM"`.

## Review Log

### Pass 1 — 2026-08-19

Reviewers: Blind Hunter (adversarial-general) + Edge Case Hunter (parallel)

| Category | Count | Severity breakdown |
|----------|-------|--------------------|
| patch | 3 | low:3 |
| defer | 2 | low:2 |
| reject | 5 | low:5 |
| intent_gap | 0 | — |
| bad_spec | 0 | — |

**Patches applied:**
- F-plant-null: added `machine.getPlant() == null` guard before `getPlant().getId()` in `SparepartAlertService`
- F-toctou: wrapped `alertRepository.save()` in `catch(DataIntegrityViolationException)` for idempotent concurrent-alert skip
- F-import: added `DataIntegrityViolationException` import

**Deferred:**
- DW: `threshold_percentage` CHECK constraint (0-100) in DB — domain validation belongs to Story 2.6 installation creation
- DW: `evaluateAll` + alert creation in one catch block (observability improvement, not blocking)

**Rejected:**
- boundary `compareTo < 0`: correct — alert fires at `>= threshold` (== 0 means equal = fire)
- partial index PostgreSQL-only: documented Residual Risk in spec
- audit Propagation coupling: alert+audit atomic in one tx is correct design
- traceId null: telemetry system guarantees non-null
- ACKNOWLEDGED blocks re-eval: by design, correct lifecycle

**Verification:** `mvn compile test -Dtest=SparepartAlertServiceTest` → BUILD SUCCESS, 5/5 pass

## Verification

**Commands:**
- `mvn -pl syncro/apps/backend test -Dtest=SparepartAlertServiceTest` -- expected: BUILD SUCCESS, all tests green
- `mvn -pl syncro/apps/backend compile` -- expected: BUILD SUCCESS, no compilation errors
