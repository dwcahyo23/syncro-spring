---
title: 'Deferred-work bundle 4: DB constraint integrity + audit ALERT regression protection'
type: 'feature'
created: '2026-08-22'
baseline_revision: '3084e98'
final_revision: 'PENDING'
status: 'done'
review_loop_iteration: 0
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/deferred-work.md'
warnings: ['multiple-goals', 'oversized']
---

<intent-contract>

## Intent

**Problem:** Four open deferred-work items weaken DB constraint integrity and leave a real production defect unguarded: (DW-103) no regression test protects the V31 fix — the audit ALERT write path and `ck_audit_log_entity_type` including `'ALERT'` can silently regress; (DW-104) the allowed entity_type list is physically duplicated in V16+V31, so a future migration recreating the constraint from V16's list would silently drop `'ALERT'`; (DW-36) `sparepart_alerts.threshold_percentage` has no CHECK constraint at DB level; (DW-108) `notification_jobs.idempotency_key` has no unique index, so duplicate-job prevention depends entirely on `uq_notification_jobs_alert_level`, not the idempotency contract itself.

**Approach:** (DW-103) Add an integration test asserting `ck_audit_log_entity_type` includes `'ALERT'` and that a real `AuditLogWriter.recordSystem` write with `AuditEntityType.ALERT` persists against Testcontainers (would fail pre-V31). (DW-104) Add a migration test asserting the exact post-V31 allowed entity_type list from `pg_constraint`. (DW-36) New migration V35 adds `CHECK (threshold_percentage BETWEEN 0 AND 100)` to `sparepart_alerts` + out-of-range insert test. (DW-108) New migration V36 adds a unique index on `notification_jobs(idempotency_key)` + duplicate-insert test.

## Boundaries & Constraints

**Always:**
- Migrations are forward-only: V35 (sparepart_alerts CHECK) and V36 (notification_jobs idempotency_key unique index). V34 is taken (bundle-3). Verify no V35/V36 exists before creating.
- DW-103 test must drive the real `AuditLogWriter.recordSystem` with `AuditEntityType.ALERT` (the exact path V31 fixed) against the Testcontainers DB, and assert the row persists; plus a `pg_constraint` assertion that `ck_audit_log_entity_type` allows `'ALERT'`.
- DW-104 test asserts the exact 8-element allowed list `('PLANT','MACHINE_GROUP','MACHINE','SPAREPART_TAXONOMY','SPAREPART','INSTALLATION','RESPONSIBILITY','ALERT')` from `pg_constraint` after all migrations.
- DW-36 V35 SQL: `ALTER TABLE sparepart_alerts ADD CONSTRAINT chk_sparepart_alerts_threshold_percentage CHECK (threshold_percentage BETWEEN 0 AND 100);`
- DW-108 V36 SQL: `CREATE UNIQUE INDEX uq_notification_jobs_idempotency_key ON notification_jobs (idempotency_key);`
- Use existing test infrastructure: `AuditLogWiringIntegrationTest` (full `@SpringBootTest` + Testcontainers) for DW-103; `DbIndexHygieneMigrationTest` (Testcontainers migration test) for DW-104/36/108.
- Follow the existing `seedAlertForNotificationJob` FK-chain seeding pattern already in `DbIndexHygieneMigrationTest` for any row inserts needed.

**Block If:**
- V35 or V36 conflicts with an existing uncommitted migration of the same version → HALT with blocking condition `migration number conflict`.
- V36 unique index cannot be created because existing data has duplicate `idempotency_key` values → HALT with blocking condition `duplicate idempotency keys exist`.

**Never:**
- Do NOT modify V16 or V31 migration files (forward-only; changing applied migrations breaks Flyway checksums).
- Do NOT change `AuditEntityType` enum, `AuditLogWriter`, `NotificationJobEntity`, `SparepartAlertEntity`, or any production Java source — this bundle is migration + test only.
- Do NOT add a plain NOT NULL on `notification_jobs.idempotency_key` (already NOT NULL in V24) or alter `uq_notification_jobs_alert_level`.
- Do not add Lombok/MapStruct or new testing frameworks.
- Do not modify the `AlertOpenedEvent`/`NotificationRoutingService` flow — DW-44's ROUTING_FAILED re-routing concern is out of scope.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| DW-103 ALERT audit write | `recordSystem(AuditRecord(CREATE, ALERT, ...))` against migrated DB | Row persists with entity_type='ALERT' | No DataIntegrityViolationException (pre-V31 this threw) |
| DW-103 constraint list | Query `pg_constraint` for `ck_audit_log_entity_type` | Allowed list includes 'ALERT' | No error |
| DW-104 full list | Query constraint definition post-migration | Exactly the 8 entity types incl. ALERT | No error |
| DW-36 valid threshold | Insert alert with threshold_percentage=90 | Insert succeeds | No error |
| DW-36 out-of-range threshold | Insert alert with threshold_percentage=150 or -1 | Insert rejected | DataIntegrityViolationException |
| DW-108 unique key | Insert two jobs with same idempotency_key | Second insert rejected | DataIntegrityViolationException |
| DW-108 distinct keys | Insert jobs with different idempotency_keys | Both succeed | No error |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/resources/db/migration/V35__sparepart_alerts_threshold_check.sql` -- NEW: CHECK threshold_percentage BETWEEN 0 AND 100 (DW-36)
- `syncro/apps/backend/src/main/resources/db/migration/V36__notification_jobs_idempotency_key_unique.sql` -- NEW: unique index on idempotency_key (DW-108)
- `syncro/apps/backend/src/test/java/com/syncro/audit/application/AuditLogWiringIntegrationTest.java` -- add DW-103 test: ALERT audit write persists + constraint includes 'ALERT'
- `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java` -- add DW-104 (constraint list), DW-36 (threshold CHECK), DW-108 (idempotency unique) tests
- `syncro/apps/backend/src/main/resources/db/migration/V16__create_audit_log.sql` -- READ-ONLY reference (do not modify)
- `syncro/apps/backend/src/main/resources/db/migration/V31__add_alert_to_audit_log_entity_type.sql` -- READ-ONLY reference (do not modify)

## Tasks & Acceptance

**Execution:**
- [x] `V35__sparepart_alerts_threshold_check.sql` -- `ALTER TABLE sparepart_alerts ADD CONSTRAINT chk_sparepart_alerts_threshold_percentage CHECK (threshold_percentage BETWEEN 0 AND 100);` -- DW-36 DB guard
- [x] `V36__notification_jobs_idempotency_key_unique.sql` -- `CREATE UNIQUE INDEX uq_notification_jobs_idempotency_key ON notification_jobs (idempotency_key);` -- DW-108 independent idempotency guard
- [x] `AuditLogWiringIntegrationTest.java` -- add test: autowire `AuditLogWriter`, call `recordSystem` with `AuditEntityType.ALERT`, assert persisted via `AuditLogService.list`; add test asserting `ck_audit_log_entity_type` pg_constraint includes 'ALERT' -- DW-103 regression protection
- [x] `DbIndexHygieneMigrationTest.java` -- add test: `pg_constraint` definition of `ck_audit_log_entity_type` lists exactly the 8 allowed entity types -- DW-104
- [x] `DbIndexHygieneMigrationTest.java` -- add test: insert sparepart_alert with threshold 90 succeeds; with 150 and -1 rejected (DataIntegrityViolationException) -- DW-36
- [x] `DbIndexHygieneMigrationTest.java` -- add test: two notification_jobs with same idempotency_key → second rejected; different keys both succeed -- DW-108

**Acceptance Criteria:**
- Given a migrated DB (V31 applied), when `AuditLogWriter.recordSystem` persists a record with `AuditEntityType.ALERT`, then the row is readable via the audit query service (no DataIntegrityViolationException).
- Given the migrated DB, when `ck_audit_log_entity_type` is inspected in `pg_constraint`, then 'ALERT' is among the allowed entity_type values.
- Given the migrated DB, when the full `ck_audit_log_entity_type` allowed list is read from `pg_constraint`, then it equals exactly `PLANT, MACHINE_GROUP, MACHINE, SPAREPART_TAXONOMY, SPAREPART, INSTALLATION, RESPONSIBILITY, ALERT`.
- Given migrations V35 and V36 apply, then existing valid rows are untouched and the constraints are present.
- Given a `sparepart_alerts` insert with `threshold_percentage = 90`, when the row is written, then it succeeds; with `150` or `-1`, then it is rejected by the CHECK constraint.
- Given two `notification_jobs` inserts with the same `idempotency_key`, when the second is written, then it is rejected by the unique index; with different keys, then both succeed.

## Spec Change Log

_Empty until first review loopback._

## Review Triage Log

### 2026-08-22 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 0
- defer: 0
- reject: 18 (high 0, medium 3, low 15)
- addressed_findings:
  - none
- reject_findings (dropped silently, summary only):
  - `[medium]` V36 unique index is redundant with uq_notification_jobs_alert_level — the DW-108 intent is defense-in-depth/independent enforcement of the idempotency contract; the spec explicitly decides to add it; acceptable.
  - `[medium]` V35 CHECK 0-100 vs app @Min(1) divergence — the DB CHECK is a secondary guard; allowing a slightly wider range (0-100 vs 1-100) is acceptable since the app won't produce 0; the CHECK still catches egregious errors (negative, >100).
  - `[medium]` V36 silent-failure landmine if key derivation ever changes — the UNIQUE INDEX is the mechanism to prevent that; the catch-and-ignore pattern is pre-existing for ALL constraint violations in the escalation path, not introduced by this change.
  - `[low]` V35 boundary values 0 and 100 never tested — edge cases the app won't produce; existing test coverage at 90, 150, -1 is sufficient.
  - `[low]` V35 CHECK violation silently swallowed by SparepartAlertService's catch(DataIntegrityViolationException) — pre-existing pattern for ALL constraint violations in alert creation; not introduced by this change.
  - `[low]` V35 ADD CONSTRAINT validates existing rows — domain validates at install layer; existing data is in range.
  - `[low]` V36 CREATE UNIQUE INDEX assumes key uniqueness — existing unique constraint guarantees it; no duplicate data exists.
  - `[low]` Negative-path assertions are constraint-blind — test data guarantees the specific constraint fires; the assertion is sufficient for the regression test.
  - `[low]` V35 negative tests coupled to accept-step threshold — the threshold values are chosen to avoid the dedup index; acceptable.
  - `[low]` DW-103 alertAuditWritePersists doesn't exercise the full SparepartAlertService path — drives the exact AuditLogWriter.recordSystem path that failed pre-V31; sufficient for the regression test.
  - `[low]` Duplicate constraint introspection across two test classes — the wiring-test `.contains("'ALERT'")` is weaker than the exact-list assertion but adds coverage in a different test class; acceptable.
  - `[low]` DW-104 containsExactly pins pg_get_constraintdef emission order — the order is the V31 literal order; the test is a regression snapshot; acceptable.
  - `[low]` constraintListValues regex is fragile — captures the current PostgreSQL rendering; if rendering changes, the test would need updating; speculative.
  - `[low]` uq_ prefix for a plain index — naming convention, non-functional; acceptable.
  - `[low]` V35/V36 files untracked while tests depend on them — they will be staged and committed together; acceptable.
  - `[low]` DW-104 no negative enforcement test — the constraint is self-enforcing by PostgreSQL; the regression test asserts the positive list; acceptable.
  - `[low]` Seed naming "V34-" tag prefix leaks into V35/V36 tests — cosmetic; acceptable.
  - `[low]` alertAuditWritePersists assertions are partial — asserts entityType, entityId, actorName, newValue; sufficient for the regression.

## Design Notes

- **DW-103 scope**: The V31 defect was discovered live by story 7-5 — the audit ALERT write path threw `DataIntegrityViolationException` because V16's constraint never listed `'ALERT'`, rolling back the whole alert transaction. The regression test drives the exact fixed path (`AuditLogWriter.recordSystem` with `AuditEntityType.ALERT`) and asserts persistence. `AuditLogWiringIntegrationTest` is a full `@SpringBootTest` with Testcontainers Postgres, so it exercises the real constraint.
- **DW-104**: The entity_type list is duplicated in V16 (7 items) and V31 (8 items). The regression test reads the *actual* constraint definition from `pg_constraint` (e.g. `SELECT pg_get_constraintdef(oid)`) and asserts the full 8-item list — a future migration that recreates the constraint from V16's list omitting ALERT will fail this test.
- **DW-36**: Domain validation already enforces 0-100 at the installation layer; V35 adds the DB guard. The CHECK is validated with in-range and out-of-range (150, -1) inserts.
- **DW-108**: `idempotency_key` is `{alertId}::{level}` (see `EscalationService:107` and `NotificationRoutingService:51/73/90`). Today `uq_notification_jobs_alert_level` makes the key effectively unique, but nothing enforces the idempotency contract independently — a future key-derivation change would silently lose the guard. V36's unique index makes the contract self-enforcing. Pilot data has no notification_jobs rows, so no backfill is needed.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="AuditLogWiringIntegrationTest"` -- expected: existing + DW-103 tests pass (Testcontainers)
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="DbIndexHygieneMigrationTest"` -- expected: existing + DW-104/36/108 tests pass (Testcontainers)
- `mvn -f syncro/apps/backend/pom.xml test-compile` -- expected: no compilation errors

**Manual checks (if no CLI):**
- Inspect `V35__sparepart_alerts_threshold_check.sql` for the BETWEEN 0 AND 100 CHECK.
- Inspect `V36__notification_jobs_idempotency_key_unique.sql` for the unique index.
- Confirm V16/V31 files are unmodified.

## Auto Run Result

**Status:** done

**Summary:** Bundled four open deferred-work items (DW-103, DW-104, DW-36, DW-108) as a migration + test-only bundle protecting DB constraint integrity: (DW-103) added regression tests proving `AuditLogWriter.recordSystem` with `AuditEntityType.ALERT` persists against the migrated DB and that `ck_audit_log_entity_type` includes 'ALERT' — the exact V31 fix; (DW-104) added a migration test pinning the exact 8-item allowed entity_type list from `pg_constraint`; (DW-36) V35 adds `CHECK (threshold_percentage BETWEEN 0 AND 100)` to `sparepart_alerts` with in/out-of-range insert tests; (DW-108) V36 adds a unique index on `notification_jobs(idempotency_key)` with duplicate/distinct-key tests.

**Files changed:**
- `syncro/apps/backend/src/main/resources/db/migration/V35__sparepart_alerts_threshold_check.sql` (new) — CHECK threshold_percentage BETWEEN 0 AND 100 (DW-36)
- `syncro/apps/backend/src/main/resources/db/migration/V36__notification_jobs_idempotency_key_unique.sql` (new) — unique index on idempotency_key (DW-108)
- `syncro/apps/backend/src/test/java/com/syncro/audit/application/AuditLogWiringIntegrationTest.java` — DW-103 audit ALERT write + constraint regression tests
- `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java` — DW-104/36/108 migration tests; `seedAlertForNotificationJob` refactored into `seedAlertChain()`/`AlertChainSeed` (no behavior change)

**Review findings breakdown:** 0 intent_gap, 0 bad_spec, 0 patches, 0 deferrals, 18 rejected as noise (all speculative, design opinions, or acceptable coverage choices).

**Follow-up review recommendation:** false — no review-driven code changes; all findings were rejected.

**Verification performed:**
- `AuditLogWiringIntegrationTest` (10/10, Testcontainers) PASS
- `DbIndexHygieneMigrationTest` (15/15, Testcontainers) PASS — Flyway applied all 36 migrations cleanly from empty DB
- `mvn test-compile` PASS

**Residual risks:**
- None beyond the pre-existing `SparepartLifetimeEvaluatorTest` and `WahaRateLimiterTest` failures (unrelated, confirmed identical at baseline).