---
workflowStatus: 'draft'
runId: '20260808-012337-779e'
story: 'dw-db-index-hygiene'
storyKey: 'dw-db-index-hygiene'
baselineRevision: 'a30e7f6c8466f36a08fc8008123dd78b56557210'
finalRevision: 'd55d6d1'
lastSaved: '2026-08-08'
---

# Test Design: Story dw-db-index-hygiene - Drop redundant DB indexes, add ORDER BY supporting indexes (V17)

**Date:** 2026-08-08
**Author:** Yusuf (TEA / bmad-loop story test-design run)
**Status:** Draft
**Mode:** Story-level (deferred-work sweep bundle; DW-5 + DW-6). Companion to epic-level design in `test-design-epic-2.md`.

---

## 1. Executive Summary

**Scope:** `dw-db-index-hygiene` is a schema-only chore: one forward-only Flyway migration (V17) drops six redundant database indexes and creates three ORDER BY-supporting indexes, plus a Testcontainers migration test proving schema state and query ordering. No application Java or frontend code changes.

**What the working tree contains (evidence base):**

- `syncro/apps/backend/src/main/resources/db/migration/V17__drop_redundant_indexes_add_query_indexes.sql` — drops `idx_machine_sparepart_installations_machine_id_sparepart_id`, `uq_auth_user_plant_assignments_auth_user_plant` (via `DROP CONSTRAINT`), `idx_auth_user_plant_assignments_auth_user_id`, `idx_machine_groups_plant_id`, `idx_machines_plant_id`, `idx_sparepart_taxonomy_dimension`; creates `idx_spareparts_code`, `idx_machines_code`, `idx_sparepart_taxonomy_dimension_name`.
- `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java` — 6 tests: catalog introspection (dropped absent, created present with exact `USING btree` definitions, kept PK/unique/single-column indexes intact) + 2 behavioral tests (taxonomy ORDER BY name, machine list ORDER BY code) via the real repositories.
- `_bmad-output/implementation-artifacts/spec-db-index-hygiene.md` — intent contract, acceptance criteria, design notes, review log.
- `_bmad-output/implementation-artifacts/deferred-work.md` — DW-5/DW-6 closed as resolved by this bundle (uncommitted edit in the working tree).

**Risk summary:**

- Total risks identified: 8
- High-priority risks (>=6): 1 (pre-existing query-design concern surfaced by the change)
- Critical categories: DATA (dropped-coverage safety), PERF (index usefulness), OPS (migration lock), TECH (test harness heaviness)

**Coverage summary:**

- P0 scenarios: 3 (already covered by shipped tests — 0 new effort)
- P1 scenarios: 3 (~4-8 h, incl. upgrade-path test)
- P2/P3 scenarios: 6 (~4-10 h, incl. EXPLAIN-based validation + ops notes)
- **Total effort**: ~8-18 h incremental (the shipped migration test already covers the P0 core)

**Bottom line:** The change is well-tested at the schema level. The dominant residual risk is *performance*, not correctness: the new bare `(code)` indexes may under-deliver for plant-scoped ORDER BY, and `machines.code` pagination is non-deterministic across plants. Both are flagged for follow-up, not blockers for this chore.

---

## 2. Inputs Reviewed

- `_bmad-output/implementation-artifacts/spec-db-index-hygiene.md`
- `_bmad-output/implementation-artifacts/deferred-work.md` (DW-5, DW-6)
- Migrations: `V2__create_plant_scope_foundation.sql`, `V3__create_machine_groups.sql`, `V5__create_machines.sql`, `V6__create_sparepart_taxonomy.sql`, `V7__create_spareparts.sql`, `V8__create_machine_sparepart_installations.sql`, `V12__relax_sparepart_name_and_add_installation_function.sql`, `V17__drop_redundant_indexes_add_query_indexes.sql`
- Repos: `SparepartRepository.java`, `MachineRepository.java`, `SparepartTaxonomyRepository.java`
- Test: `DbIndexHygieneMigrationTest.java`; Testcontainers pattern in `MachineServiceIntegrationTest.java`
- Config: `_bmad/tea/config.yaml` (`risk_threshold: p1`)

**Existing test coverage (already shipped with the change):**

- `DbIndexHygieneMigrationTest` — 6 tests, 0 failures (catalog + behavioral; verified in spec `Auto Run Result`)
- `MachineServiceIntegrationTest` — 13 tests PASS (regression for machine list/join queries)
- `SparepartServiceIntegrationTest` — 15 tests PASS
- `SparepartTaxonomyServiceIntegrationTest` — 11 tests PASS

---

## 3. Risk Assessment

Score = Probability x Impact (1-3 each). Priority threshold per config: `risk_threshold: p1`.

| ID | Category | Risk | P | I | Score | Priority |
|---|---|---|---:|---:|---:|---|---|
| DH-04 | DATA | `machines.code` is unique only per `(plant_id, lower(code))`; `ORDER BY code` without a tiebreaker makes OFFSET pagination non-deterministic across plants. **Pre-existing**, but the new `idx_machines_code` makes ordering more attractive to the planner, increasing the chance this surfaces. | 3 | 2 | 6 | **P1** |
| DH-07 | DATA | The shipped test only proves V17 on a **fresh DB**. The production upgrade path (V1-V16 already applied, real data present) is not directly exercised; a DDL interaction with existing objects would surface only at deploy time. | 2 | 2 | 4 | **P1** |
| DH-02 | PERF | New `idx_machines_code` / `idx_spareparts_code` are bare `(code)` indexes. The scoped machine query filters `plant_id IN (...)` first, so a composite `(plant_id, code)` would serve ORDER BY better; the shipped indexes may deliver less of the stated benefit. | 2 | 2 | 4 | P2 |
| DH-03 | OPS | `CREATE INDEX` (non-`CONCURRENTLY`) takes a `SHARE` lock that blocks writes on the table during index build; on hot/large tables this is a maintenance-window concern at production apply. | 2 | 2 | 4 | P2 |
| DH-05 | TECH | `@SpringBootTest` boots the full app context (Redis/InfluxDB/MQTT/WAHA beans) for a schema test — slow and heavier than needed; also `JwtTokenService.java` is gitignored so a clean checkout cannot compile the backend module (pre-existing repo issue). | 2 | 2 | 4 | P2 |
| DH-01 | DATA | A kept object enumerated in the spec or a PK/unique/FK-supporting index is accidentally dropped or altered. Mitigated by the exact-set intent contract and the kept-object catalog test, but any future migration reusing these names is a regression surface. | 1 | 3 | 3 | P2 |
| DH-06 | DATA | Behavioral ORDER BY tests use `containsSubsequence` (allows interleaved/extra rows) and rely on DB collation; a weak assertion could mask an ordering regression. | 1 | 2 | 2 | P2 |
| DH-08 | OPS | Idempotency via `IF EXISTS`/`IF NOT EXISTS` is safe for re-runs, but a future migration that assumes a dropped index will silently mis-plan. Forward-only discipline is the only guard. | 1 | 2 | 2 | P3 |

### Risk Testability Notes

- Every risk above is testable with the existing harnesses (Testcontainers + JdbcTemplate + EXPLAIN), except DH-03/DH-08 which are ops/process risks (documentation + discipline, not tests).
- DH-04 and DH-02 are **performance/design** risks; validating them requires EXPLAIN-plan or behavioral pagination probes, not just catalog assertions. Both are pre-existing or intent-contract-pinned and are follow-up items, not this chore's blockers.
- DH-01 is the headline *correctness* risk the shipped test already de-risks (kept-object assertions + exact index-definition assertions).

---

## 4. Risk-Based Coverage Strategy

Prioritization: schema correctness first (already covered by shipped tests), then upgrade-path and plan-level validation, then exploratory/ops.

### P0 - Critical (must pass; already covered by shipped tests — verify, do not re-build)

| ID | Scenario | Level | Evidence |
|---|---|---|---|
| T-DH-P0-01 | Fresh DB runs V1-V17; the 6 redundant indexes are absent (`redundantIndexesAreDropped`) | Backend int (Testcontainers) | `DbIndexHygieneMigrationTest` — existing |
| T-DH-P0-02 | The 3 new indexes exist with exact `USING btree (code)` / `USING btree (dimension, name)` definitions; kept PK/unique/FK-supporting indexes intact (`queryIndexesArePresentWithExpectedDefinitions`, `keptConstraintsAndUniqueIndexesStillExist`, `keptSingleColumnIndexesStillExist`) | Backend int | `DbIndexHygieneMigrationTest` — existing |
| T-DH-P0-03 | ORDER BY preserved after V17: taxonomy `findByDimensionOrderByNameAsc` and machine list `findAllScoped`/`findAllUnscoped` (`taxonomyOrderingPreservedAfterV17`, `machineListOrderingPreservedAfterV17`) | Backend int | `DbIndexHygieneMigrationTest` — existing |

**P0 gate:** re-run `DbIndexHygieneMigrationTest` + the three service integration suites (commands below). 100% pass required.

### P1 - High (incremental work to close the real gaps)

| ID | Scenario | Level | Gap closed | Effort |
|---|---|---|---|---|
| T-DH-P1-01 | **Upgrade-path probe**: start from a DB migrated only to V16, seed representative rows (plant/group/machine/taxonomy/assignment/installation), then apply V17 and assert all 6 drops + 3 creates succeed, data intact, kept objects present. | Backend int | DH-07 | 2-4h |
| T-DH-P1-02 | **Pagination-determinism probe**: seed machines with duplicate `code` across two plants, page through `findAllScoped`/`findAllUnscoped` with `ORDER BY code`, document whether ordering/tie-breaking is stable; capture the decision (add tiebreaker or accept + document). | Backend int / exploratory | DH-04 | 1-2h |
| T-DH-P1-03 | **Plan-level validation**: `EXPLAIN` the scoped machine query and the sparepart search default sort; confirm whether `idx_machines_code`/`idx_spareparts_code` is actually used for ORDER BY (or whether the filter on `plant_id` makes it moot); record evidence for a future composite-index tuning decision. | Backend int / EXPLAIN | DH-02 | 1-2h |

### P2 - Medium (deferred unless evidence is cheap)

| ID | Scenario | Level | Gap closed | Effort |
|---|---|---|---|---|
| T-DH-P2-01 | Assert dropped objects are unreferenced anywhere (app code, tests, seeds, other migrations) via a repo-wide grep as a static guard. | Static | DH-01 | 0.5h |
| T-DH-P2-02 | Strengthen behavioral assertions from `containsSubsequence` to exact expected list when the seed is the only data present. | Backend int | DH-06 | 0.5h |
| T-DH-P2-03 | Document the `SHARE`-lock/write-block window and maintenance-window recommendation for production apply of V17; confirm `CONCURRENTLY` not required at current table scale. | Ops note | DH-03 | 0.5h |
| T-DH-P2-04 | Note the lightweight Flyway+JDBC Testcontainers alternative to `@SpringBootTest` for future schema tests. | TECH note | DH-05 | 0.5h |

### P3 - Low / Ops (documentation, not tests)

| ID | Scenario | Level |
|---|---|---|
| T-DH-P3-01 | Migration-discipline note: no future migration may assume the dropped indexes exist; `IF EXISTS`/`IF NOT EXISTS` remain the guard. | Process |
| T-DH-P3-02 | Clean-checkout compile blocker (`JwtTokenService` gitignored) tracked separately; not this chore's scope. | Process |

---

## 5. Traceability to Acceptance Criteria (spec)

| AC (from spec) | Covered by |
|---|---|
| AC1: 6 redundant indexes absent after fresh V1-V17 | T-DH-P0-01 |
| AC2: 3 new indexes present with expected column lists | T-DH-P0-02 |
| AC3: `findByDimensionOrderByNameAsc` orders by name asc | T-DH-P0-03 |
| AC4: `findAllScoped`/`findAllUnscoped` list correctly with joins | T-DH-P0-03 |

**Unmapped / partial:**
- Upgrade-from-existing-schema apply (project rule "from previous schema state") → T-DH-P1-01 (new)
- Actual query performance benefit of the new indexes → T-DH-P1-03 (plan evidence, not a hard threshold)

---

## 6. NFR Planning (performance & reliability in scope)

| NFR Category | In Scope? | Threshold | Risk Link | Planned Validation | Evidence Needed |
|---|---|---|---|---|---|
| Performance (write cost) | Yes | Reduced write amplification from 6 redundant indexes; no hard latency threshold | DH-01 | Catalog assertions + FK-support coverage | Migration test report |
| Performance (read/ORDER BY) | Yes | New indexes actually used by hot list/join ORDER BY paths | DH-02 | `EXPLAIN` plan probe | EXPLAIN output |
| Reliability (migration) | Yes | V17 applies cleanly from empty DB and from V16 state; forward-only, idempotent | DH-07 | Testcontainers fresh + upgrade-path runs | Maven/Testcontainers logs |
| Reliability (ordering) | Yes | No ordering regression in list queries | DH-06 | Behavioral repository tests | JUnit report |
| Maintainability | Yes | No new framework/package; tests isolated, deterministic, <1.5 min each | DH-05 | Test review + suite timing | CI report |

**Unknown thresholds (do not invent):** no explicit query-latency or pagination-size SLO exists in the repo; performance verdict is limited to index-usefulness evidence, deferred to `nfr-assess` after plan-level evidence exists.

---

## 7. Execution Strategy

- **PR:** `DbIndexHygieneMigrationTest` + `MachineServiceIntegrationTest` + `SparepartServiceIntegrationTest` + `SparepartTaxonomyServiceIntegrationTest` — full functional set, well under 15 min.
- **PR:** T-DH-P1-01 upgrade-path probe once written.
- **Nightly/Weekly:** `EXPLAIN` plan validation and pagination-determinism probes (P1-02, P1-03) — cheap but not per-commit.
- **Manual/ops:** lock-window note and migration discipline (P2-03, P3-01) confirmed at deploy planning.

## 8. Resource Estimates (ranges only)

- P0: already shipped (~0 h incremental)
- P1: ~4-8 h
- P2: ~2-4 h
- P3: ~0-2 h (docs/process)
- **Total:** ~6-14 h (~1-2 days), backend-integration-heavy, no frontend impact

## 9. Quality Gates

- P0 pass rate = 100% (existing tests + service regression suites)
- P1 pass rate >= 95%; new P1 tests (upgrade path, plan probe, pagination probe) pass or produce documented decision evidence
- High-risk mitigations: DH-04 decision documented (tiebreaker or accepted) before this story is considered fully risk-closed
- Coverage target >= 80% on the migration change (catalog + behavioral already exceeds this)
- NFR evidence: fresh-DB + upgrade-path + EXPLAIN evidence exists for PERF/RELIABILITY; final PASS/CONCERNS/FAIL deferred to `nfr-assess`

## 10. Interworking & Regression

| Component | Impact | Regression scope |
|---|---|---|
| `machines` list queries (`MachineRepository.findAllScoped/findAllUnscoped`) | ORDER BY `code` relies on `idx_machines_code`; scoped filter on `plant_id` unchanged | `MachineServiceIntegrationTest` |
| `spareparts` search (`SparepartRepository.search`) | Default `sort=code` benefits from `idx_spareparts_code` | `SparepartServiceIntegrationTest` |
| `sparepart_taxonomy` (`findByDimensionOrderByNameAsc`) | `idx_sparepart_taxonomy_dimension_name (dimension, name)` | `SparepartTaxonomyServiceIntegrationTest` |
| FK enforcement (all 5 affected tables) | Kept PK/unique/single-column leading-column indexes cover FKs | `keptConstraintsAndUniqueIndexesStillExist` + `keptSingleColumnIndexesStillExist` |
| Auth plant-scope assignment queries | `idx_auth_user_plant_assignments_auth_user_id` removed; PK `(auth_user_id, plant_id)` leading column covers | `PlantScopeServiceTest`, `PlantScopeRepositoryIntegrationTest` |
| Deferred-work ledger | DW-5/DW-6 marked resolved | None (ledger close-out) |

---

## 11. Recommended Follow-Up Work (gaps discovered)

1. **Add the V16→V17 upgrade-path probe** (T-DH-P1-01) — the only uncovered project rule ("from previous schema state").
2. **Decide the pagination tiebreaker for `ORDER BY code`** (DH-04): add a deterministic secondary sort key or document acceptance.
3. **Plan-level EXPLAIN evidence** for whether bare `(code)` indexes are actually used; if not, record the future composite `(plant_id, code)` tuning item.
4. **Track the clean-checkout compile blocker** (`JwtTokenService` gitignored) separately from this chore.
5. **Document the V17 `SHARE`-lock maintenance window** for production apply.

## 12. Verification Commands

- `$env:JAVA_HOME="C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"; mvn -q -f syncro/apps/backend/pom.xml test -Dtest="DbIndexHygieneMigrationTest"` — PASS expected (proven in spec Auto Run Result)
- `$env:JAVA_HOME="C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"; mvn -q -f syncro/apps/backend/pom.xml test -Dtest="MachineServiceIntegrationTest,SparepartServiceIntegrationTest,SparepartTaxonomyServiceIntegrationTest"` — PASS expected
- Manual: confirm `V17__drop_redundant_indexes_add_query_indexes.sql` is the only migration altering indexes and no V1-V16 file is modified.

**Generated by**: BMad TEA Agent - Test Architect Module
**Workflow**: `bmad-testarch-test-design` (story-level run)
