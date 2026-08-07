---
title: 'db-index-hygiene'
type: 'chore'
created: '2026-08-08'
status: 'done'
baseline_revision: 'a30e7f6c8466f36a08fc8008123dd78b56557210'
final_revision: 'a7faa33'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
---

<intent-contract>

## Intent

**Problem:** Redundant DB indexes waste write performance (six indexes duplicate PKs, unique constraints, or unique-index prefixes), while hot join + ORDER BY list queries in `SparepartRepository.search`, `MachineRepository.findAllScoped/findAllUnscoped`, and `SparepartTaxonomyRepository.findByDimensionOrderByNameAsc` order on columns with no supporting index.

**Approach:** Add one forward-only Flyway migration (V17) that drops the six redundant indexes and adds three order-by-supporting indexes for the hot queries, then prove schema state and query ordering with a Testcontainers migration test.

## Boundaries & Constraints

**Always:**
- Only one new Flyway migration `V17__drop_redundant_indexes_add_query_indexes.sql`; migrations are forward-only, applied migrations are never edited, and no `ddl-auto=update`.
- Drop exactly: `idx_machine_sparepart_installations_machine_id_sparepart_id`, `uq_auth_user_plant_assignments_auth_user_plant`, `idx_auth_user_plant_assignments_auth_user_id`, `idx_machine_groups_plant_id`, `idx_machines_plant_id`, `idx_sparepart_taxonomy_dimension`.
- Add exactly: `idx_spareparts_code`, `idx_machines_code`, `idx_sparepart_taxonomy_dimension_name`.
- Index names follow `idx_<table>_<columns>`; the dropped constraint is dropped via `ALTER TABLE ... DROP CONSTRAINT`.
- Migration evidence via Testcontainers (never mock persistence for migration/index verification); integration test must prove the new migration applies cleanly after the full existing chain and that query ordering is preserved.

**Block If:**
- Any dropped index is still referenced by application code, a test, a seed script, or another migration. (Investigation shows none; verify during implementation.)

**Never:**
- Do not edit the deferred-work ledger (`_bmad-output/implementation-artifacts/deferred-work.md`).
- Do not modify V1-V16 migrations.
- Do not touch application Java code or frontend.
- Do not drop `idx_machine_sparepart_installations_sparepart_id` (sparepart_id is not a prefix of the unique index), `idx_machine_sparepart_installations_machine_id` (out of enumerated scope), or `idx_auth_user_plant_assignments_plant_id` (plant_id not leading in PK).
- Do not add indexes beyond the three enumerated ones.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | Fresh DB runs migrations V1-V17 | All migrations apply; 6 redundant indexes absent; 3 new indexes present; unique constraints/PKs intact | No error expected |
| ORDER_PRESERVED | `findByDimensionOrderByNameAsc(CATEGORY)` after V17 | Rows ordered by `name` asc within dimension, as before | No error expected |
| JOIN_STILL_OK | `MachineRepository.findAllScoped`/`findAllUnscoped` after V17 | Machine list with joins to plant/machineGroup still returns rows ordered by `code` default | No error expected |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/resources/db/migration/V17__drop_redundant_indexes_add_query_indexes.sql` -- NEW: drops 6 redundant indexes, creates 3 order-by-supporting indexes
- `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java` -- NEW: Testcontainers test proving index presence/absence and ordering behavior
- `syncro/apps/backend/src/main/resources/db/migration/V2__create_plant_scope_foundation.sql` -- source of `uq_auth_user_plant_assignments_auth_user_plant` and `idx_auth_user_plant_assignments_auth_user_id`
- `syncro/apps/backend/src/main/resources/db/migration/V3__create_machine_groups.sql` -- source of `idx_machine_groups_plant_id`
- `syncro/apps/backend/src/main/resources/db/migration/V5__create_machines.sql` -- source of `idx_machines_plant_id`
- `syncro/apps/backend/src/main/resources/db/migration/V6__create_sparepart_taxonomy.sql` -- source of `idx_sparepart_taxonomy_dimension`
- `syncro/apps/backend/src/main/resources/db/migration/V8__create_machine_sparepart_installations.sql` -- source of `idx_machine_sparepart_installations_machine_id_sparepart_id`
- `syncro/apps/backend/src/main/resources/db/migration/V12__relax_sparepart_name_and_add_installation_function.sql` -- created `uq_machine_sparepart_installations_machine_sparepart_function` making the V8 composite index redundant
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartRepository.java` -- search() orders by `code` default
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineRepository.java` -- findAllScoped/findAllUnscoped order by `code` default
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartTaxonomyRepository.java` -- findByDimensionOrderByNameAsc
- `syncro/apps/backend/src/test/java/com/syncro/machine/application/MachineServiceIntegrationTest.java` -- Testcontainers pattern reference

## Tasks & Acceptance

**Execution:**
- [x] `syncro/apps/backend/src/main/resources/db/migration/V17__drop_redundant_indexes_add_query_indexes.sql` -- create the migration with the 6 DROP INDEX / DROP CONSTRAINT and 3 CREATE INDEX statements -- resolves DW-5 and DW-6 in one forward-only migration
- [x] `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java` -- add Testcontainers integration test asserting index absence/presence via `pg_indexes` and asserting query ordering still works -- migration/schema evidence per project rules

**Acceptance Criteria:**
- Given a fresh PostgreSQL via Testcontainers, when Flyway runs the full migration chain, then `idx_machine_sparepart_installations_machine_id_sparepart_id`, `uq_auth_user_plant_assignments_auth_user_plant`, `idx_auth_user_plant_assignments_auth_user_id`, `idx_machine_groups_plant_id`, `idx_machines_plant_id`, and `idx_sparepart_taxonomy_dimension` no longer exist.
- Given the same fresh DB, when Flyway completes, then `idx_spareparts_code`, `idx_machines_code`, and `idx_sparepart_taxonomy_dimension_name` exist with the expected column lists.
- Given the same fresh DB, when `SparepartTaxonomyRepository.findByDimensionOrderByNameAsc` runs, then taxonomy rows come back ordered by `name` ascending within the dimension.
- Given the same fresh DB, when `MachineRepository.findAllScoped`/`findAllUnscoped` run, then machines still list correctly with plant/group joins.

## Spec Change Log

<!-- Append-only. Populated by step-04 during review loops. -->

## Review Triage Log

### 2026-08-08 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 4: (high 0, medium 1, low 3)
- defer: 5: (high 0, medium 2, low 3)
- reject: 4
- addressed_findings:
  - `[medium]` `patch` Spec requires proving query ordering after V17 (AC3/AC4, task "asserting query ordering still works"), but the test was pure catalog introspection. Added `taxonomyOrderingPreservedAfterV17` and `machineListOrderingPreservedAfterV17` behavioral tests that seed rows out of insertion order and assert ORDER BY output via the actual repository methods.
  - `[low]` `patch` `indexDef` `.contains()` assertions accepted wrong column order (e.g. `(name, dimension)`) and expressions (`lower(code)`). Tightened to exact `USING btree (code)` and `USING btree (dimension, name)`.
  - `[low]` `patch` Kept-object coverage was partial (4 objects), missing `uq_sparepart_taxonomy_dimension_lower_name`, `uq_machine_groups_plant_id_name`, `uq_machine_groups_id_plant_id`, and all kept single-column indexes. Expanded assertions and added `keptSingleColumnIndexesStillExist`.
  - `[low]` `patch` `CREATE INDEX` statements were not idempotent while drops used `IF EXISTS`; added `IF NOT EXISTS` to all three creates for consistency.
  - Note: 5 defer findings are real but could not be appended to `deferred-work.md` because the intent-contract `Never` section forbids editing the deferred-work ledger; they are recorded in `## Auto Run Result` under residual risks instead.

### 2026-08-08 — Follow-up review pass
- intent_gap: 0
- bad_spec: 0
- patch: 3: (high 0, medium 1, low 2)
- defer: 8: (high 0, medium 2, low 6)
- reject: 7
- addressed_findings:
  - `[medium]` `patch` Ordering proofs in the migration test used AssertJ `containsSubsequence`, which cannot prove ORDER BY correctness against the V13-seeded `sparepart_taxonomy` CATEGORY rows interleaved with the three inserted rows (a regression that perturbs ordering while keeping the required subsequence would pass). Replaced taxonomy assertion with `isSorted()` over all returned names and machine assertions with `containsExactly("M-001", "M-002")`.
  - `[low]` `patch` `indexDef` used `queryForObject`, which throws `EmptyResultDataAccessException` when an index is missing, so the `.isNotNull()` guard was dead and a missing index failed with an opaque exception instead of an assertion; `.contains("USING btree (code)")` also accepted extra trailing columns (e.g. `(code, name)`). Made `indexDef` null-safe via `query().stream().findFirst().orElse(null)` and tightened assertions to `endsWith("USING btree (code)")` / `endsWith("USING btree (dimension, name)")`.
  - `[low]` `patch` FK enforcement after the index drops was argued in the Design Notes but never demonstrated — tests only asserted object existence. Added `referentialIntegrityStillEnforcedAfterV17`, which seeds a plant/group/machine and asserts deleting the plant throws `DataIntegrityViolationException` (ON DELETE RESTRICT).
  - Note: 8 defer findings are real but could not be appended to `deferred-work.md` because the intent-contract `Never` section forbids editing the deferred-work ledger; they are recorded in `## Auto Run Result` under residual risks instead.

## Design Notes

The V8 composite `idx_machine_sparepart_installations_machine_id_sparepart_id` is redundant because V12 replaced the `(machine_id, sparepart_id)` unique constraint with `uq_machine_sparepart_installations_machine_sparepart_function (machine_id, sparepart_id, lower(function_name))`; the leading `(machine_id, sparepart_id)` columns of that unique index cover the same access path.

`idx_auth_user_plant_assignments_auth_user_id` is redundant because `auth_user_id` is the leading column of the composite PK `(auth_user_id, plant_id)`. The PK stays (it enforces the same uniqueness that `uq_auth_user_plant_assignments_auth_user_plant` duplicated).

`idx_machine_groups_plant_id`, `idx_machines_plant_id`, and `idx_sparepart_taxonomy_dimension` are single-column indexes whose only column is the leading column of an existing unique index (`uq_machine_groups_plant_id_name`, `uq_machines_plant_id_lower_code`, `uq_sparepart_taxonomy_dimension_lower_code`/`_lower_name`), so they add write cost with no read benefit.

New indexes target the ORDER BY columns of the hot list queries:
- `idx_spareparts_code` supports the default `sort = code` of `SparepartRepository.search`.
- `idx_machines_code` supports the default `code,asc` sort of `MachineRepository.findAllScoped/findAllUnscoped`.
- `idx_sparepart_taxonomy_dimension_name (dimension, name)` supports `WHERE dimension = ? ORDER BY name ASC`.

Dropping these indexes is safe for FK enforcement: every FK on the affected tables keeps a leading-column index (the PK or the kept single-column indexes), so referential-integrity checks do not become sequential scans.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="DbIndexHygieneMigrationTest"` -- expected: PASS; proves V17 applies cleanly after V1-V16, 6 indexes gone, 3 present, ordering intact
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="MachineServiceIntegrationTest,SparepartServiceIntegrationTest,SparepartTaxonomyServiceIntegrationTest"` -- expected: PASS; regression for join/order-by query paths

**Manual checks (if no CLI):**
- Confirm `V17__drop_redundant_indexes_add_query_indexes.sql` is the only file under `db/migration` that changes indexes and that no V1-V16 file is modified.

## Auto Run Result

Status: done (follow-up review pass applied 3 patches; all verification green)

- `DbIndexHygieneMigrationTest` -- 7 tests, 0 failures, 0 errors, BUILD SUCCESS (V17 applied after V1-V16 on fresh postgres:17-alpine; 6 redundant indexes absent; 3 new indexes present with exact btree column lists; kept PKs/unique indexes/single-column FK-supporting indexes intact; taxonomy ORDER BY name verified via `isSorted()`; machine lists verified via `containsExactly`; FK enforcement (ON DELETE RESTRICT) verified by attempting to delete a plant with machines)
- `MachineServiceIntegrationTest,SparepartServiceIntegrationTest,SparepartTaxonomyServiceIntegrationTest` -- 39 tests, 0 failures, 0 errors, BUILD SUCCESS (regression for join/order-by query paths)
- Follow-up review pass (2026-08-08): 0 intent_gap, 0 bad_spec, 3 patches applied, 8 deferred, 7 rejected. Patches: (1) ordering assertions strengthened from `containsSubsequence` to `isSorted()`/`containsExactly`; (2) `indexDef` made null-safe and index-definition assertions tightened to exact `endsWith` column lists; (3) added `referentialIntegrityStillEnforcedAfterV17` proving FK enforcement survives the drops.
- Residual risks (deferred findings -- real but not this story's problem; the intent-contract `Never` section forbids editing `deferred-work.md`, so these are recorded here instead):
  - `CREATE INDEX`/`DROP INDEX` (non-`CONCURRENTLY`) take blocking locks on hot tables during the migration; inherent to the mandated one forward-only Flyway migration (CONCURRENTLY cannot run inside a Flyway transaction). Worth a maintenance window on large/hot tables.
  - `idx_machines_code` is a bare `(code)` index; the scoped machine query filters on `plant_id` first, so `ORDER BY code` is still served by a sort rather than an index walk. Intent-contract pins the exact index set, so a `(plant_id, code)` composite is a future index-tuning decision.
  - `DROP ... IF EXISTS` no-ops silently if a target index is already absent; on a drifted production DB the migration relies on the covering unique indexes existing. Tests prove the clean-path (V1-V17) case only.
  - No `EXPLAIN` plan-usage evidence in the committed tests (the only EXPLAIN assertion lives in an untracked TEA scaffold and checks `isNotBlank` only); the committed tests prove catalog state and query ordering, which is what the ACs require.
  - Search-filtered variants of the sparepart query (`lower(code) like :search`) with a leading wildcard are non-sargable and cannot use `idx_spareparts_code`; only the default no-filter ORDER BY path is index-served.
  - Dropping `uq_auth_user_plant_assignments_auth_user_plant` changes the constraint name surfaced in duplicate-insert errors; enforcement is preserved by the PK and no code references the name, so impact is limited to error-message text.
  - `machines.code` is unique per `(plant_id, lower(code))`, so `ORDER BY code` without a tiebreaker makes OFFSET pagination non-deterministic across plants -- pre-existing query-design concern, unaffected by V17.
  - `@SpringBootTest` boots the full app context (Redis/InfluxDB/MQTT/WAHA beans) for a schema test; a bare Flyway + Testcontainers JDBC test would be lighter, but this matches the project's referenced `MachineServiceIntegrationTest` pattern and passes here.
  - Untracked TEA scaffold tests (`DbIndexHygieneAtddGapScaffoldTest`, `DbIndexHygieneAtddUpgradePathScaffoldTest`) are fragile (a `containsExactly` of identical codes that can never fail, an uncapped `while(true)` pagination loop) but belong to a separate test-architect workflow and are not part of this story's committed change.
- Note: worktree build required JDK 25 from `C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot` (default JAVA_HOME was JDK 21).

