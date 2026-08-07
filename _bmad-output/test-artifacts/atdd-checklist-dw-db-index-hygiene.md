---
stepsCompleted: ['step-01-preflight-and-context', 'step-02-generation-mode', 'step-03-test-strategy', 'step-04c-aggregate', 'step-05-validate-and-complete']
lastStep: 'step-05-validate-and-complete'
lastSaved: '2026-08-08'
workflowType: 'testarch-atdd'
storyId: 'dw-db-index-hygiene'
storyKey: 'dw-db-index-hygiene'
storyFile: '_bmad-output/implementation-artifacts/spec-db-index-hygiene.md'
atddChecklistPath: '_bmad-output/test-artifacts/atdd-checklist-dw-db-index-hygiene.md'
generatedTestFiles:
  - 'syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddUpgradePathScaffoldTest.java'
  - 'syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java'
inputDocuments:
  - '_bmad-output/implementation-artifacts/spec-db-index-hygiene.md'
  - '_bmad-output/test-artifacts/test-design-story-db-index-hygiene.md'
  - '_bmad-output/test-artifacts/test-design-epic-2.md'
  - '_bmad-output/test-artifacts/test-design-progress.md'
---

# ATDD Checklist - Deferred-Work Bundle: Drop Redundant DB Indexes, Add ORDER BY Indexes (V17)

**Date:** 2026-08-08
**Author:** Yusuf (TEA / bmad-testarch-atdd)
**Primary Test Level:** Backend Integration (Testcontainers). No frontend/API surface in this story.

---

## Story Summary

Schema-only chore: a forward-only Flyway migration `V17__drop_redundant_indexes_add_query_indexes.sql` drops six redundant database indexes/constraints and creates three ORDER BY-supporting indexes, plus a Testcontainers migration test proving schema state and query ordering. No application Java or frontend code changes.

**As a** backend/platform engineer
**I want** redundant indexes removed and hot ORDER BY list queries indexed
**So that** write amplification drops without an ordering regression and list pagination is stable.

> **Run context:** Story `dw-db-index-hygiene` was already implemented and verified (`DbIndexHygieneMigrationTest` — 6 tests green). This ATDD run is a **gap-closing red-phase scaffold** produced from the story-level test design (`test-design-story-db-index-hygiene.md`): it adds RED scaffolds for the uncovered risks (DH-07 upgrade path, DH-04 pagination determinism, DH-02 EXPLAIN plan evidence, DH-06 exact-list assertions) plus an implementation checklist to close them.

---

## Acceptance Criteria

1. Given a fresh PostgreSQL via Testcontainers, when Flyway runs the full migration chain, then `idx_machine_sparepart_installations_machine_id_sparepart_id`, `uq_auth_user_plant_assignments_auth_user_plant`, `idx_auth_user_plant_assignments_auth_user_id`, `idx_machine_groups_plant_id`, `idx_machines_plant_id`, and `idx_sparepart_taxonomy_dimension` no longer exist.
2. Given the same fresh DB, when Flyway completes, then `idx_spareparts_code`, `idx_machines_code`, and `idx_sparepart_taxonomy_dimension_name` exist with the expected column lists.
3. Given the same fresh DB, when `SparepartTaxonomyRepository.findByDimensionOrderByNameAsc` runs, then taxonomy rows come back ordered by `name` ascending within the dimension.
4. Given the same fresh DB, when `MachineRepository.findAllScoped`/`findAllUnscoped` run, then machines still list correctly with plant/group joins.

---

## Story Integration Metadata

- **Story ID:** `dw-db-index-hygiene`
- **Story Key:** `dw-db-index-hygiene`
- **Story File:** `_bmad-output/implementation-artifacts/spec-db-index-hygiene.md`
- **Checklist Path:** `_bmad-output/test-artifacts/atdd-checklist-dw-db-index-hygiene.md`
- **Generated Test Files:**
  - `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddUpgradePathScaffoldTest.java`
  - `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java`

---

## Red-Phase Test Scaffolds Created

### Backend Integration Test (standalone Testcontainers + programmatic Flyway, 1 test — `@Disabled`)

**File:** `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddUpgradePathScaffoldTest.java`

This scaffold uses the lightweight Flyway + JDBC harness (T-DH-P2-04) instead of a full `@SpringBootTest`: it starts a `postgres:17-alpine` container, migrates **only to V16** via `Flyway.target(MigrationVersion.fromVersion("16"))`, seeds representative rows across every table the migration touches (plants, machine_groups, machines, sparepart_taxonomy, spareparts, machine_sparepart_installations, auth_users, auth_user_plant_assignments), then migrates to V17 and asserts drops, creates, kept objects, and data survival.

- ✅ **Test:** `DH-P1-01` V17 applies over an existing V16 schema with seeded data
  - **Status:** RED - **the flagship RED test for DH-07**. The shipped test only proves V17 on a fresh DB; the production upgrade path (V1-V16 already applied, real data present) is not directly exercised. The scaffold asserts all 6 drops + 3 creates succeed, data counts survive, and kept objects remain after a V16→V17 upgrade.
  - **Verifies:** AC1/AC2 + the project rule "from previous schema state".

### Backend Integration Tests (@SpringBootTest + Testcontainers, 3 tests — all `@Disabled`)

**File:** `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java`

- ✅ **Test:** `DH-P1-02` ORDER BY code pagination is complete and stable across duplicate codes
  - **Status:** RED - decision/documentation lock for DH-04. Seeds two plants whose machines share the identical code `M-DUP` (unique is per `(plant_id, lower(code))`, so this is legal), pages `findAllUnscoped` with `ORDER BY code` at page size 1, and asserts every row is returned exactly once. Today it is expected to pass; the **decision** (add a deterministic tiebreaker vs. accept) must be recorded on activation.
  - **Verifies:** AC4 (list correctness under a real pagination edge the new index invites).
- ✅ **Test:** `DH-P1-03` EXPLAIN shows whether the new ORDER BY indexes are actually used
  - **Status:** RED - evidence lock for DH-02. Runs `EXPLAIN (FORMAT TEXT)` on the scoped and unscoped machine `ORDER BY code` queries; asserts plans are captured (non-blank) so the plan text can be recorded. On small fixtures the planner may still seq-scan; the value is the recorded evidence that drives the future composite `(plant_id, code)` tuning decision.
  - **Verifies:** AC4 NFR (index usefulness evidence, no hard latency threshold).
- ✅ **Test:** `DH-P2-02` ORDER BY returns the exact expected list, not a subsequence
  - **Status:** RED - activation lock for DH-06. Strengthens the shipped `containsSubsequence("Alpha", "Beta", "Charlie")` to `containsExactly(...)` — a strict equality lock that fails if interleaved or extra rows appear. Expected green once activated.
  - **Verifies:** AC3 (exact ORDER BY contract).

---

## Data Factories Created

None. Both scaffolds insert rows via `JdbcTemplate` inline (existing `DbIndexHygieneMigrationTest` pattern); the upgrade-path scaffold reuses the same schema-aware SQL statements.

---

## Fixtures Created

None. `postgres:17-alpine` Testcontainers container only.

---

## Mock Requirements

None. No services/controllers exercised; the gap scaffold autowires only `JdbcTemplate`, `MachineRepository`, `SparepartTaxonomyRepository`.

---

## Required data-testid Attributes

Not applicable — no frontend surface in this story.

---

## Implementation Checklist

> Red-phase scaffolds stay `@Disabled` until a developer activates the current task. Activate ONE at a time: remove `@Disabled`, confirm it fails (red) or documents the decision, implement any fix, confirm green. Backend commands use `$env:JAVA_HOME="C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"`.

### Test: DH-P1-01 (P1) — V16→V17 upgrade-path probe (flagship)

**File:** `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddUpgradePathScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] Activate + run: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="DbIndexHygieneAtddUpgradePathScaffoldTest"`
- [ ] Confirm the seeded V16 data survives V17 (6 drops + 3 creates apply cleanly, counts intact, kept objects present). V17 uses `IF EXISTS`/`IF NOT EXISTS`, so a green is expected; any red is a real upgrade-path bug to fix in V17.
- [ ] ✅ Test passes (green phase)

**Estimated Effort:** 2-4 hours (mostly first-time harness cost)

### Test: DH-P1-02 (P1) — pagination-determinism decision

**File:** `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] Activate + run: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="DbIndexHygieneAtddGapScaffoldTest#orderByCodePaginationIsCompleteAcrossDuplicateCodes"`
- [ ] Record the DH-04 decision: either (a) add a deterministic secondary sort key (`ORDER BY code, id`) to `MachineRepository.findAllScoped/findAllUnscoped` (JPQL `ORDER BY m.code` + `id`), or (b) accept + document that OFFSET pagination across identical codes is ordering-stable only per-page and update the scaffold comment accordingly.
- [ ] If (a) is chosen, update the scaffold to assert the tiebreak (e.g. `M-DUP` from plant A precedes plant B by `id` sort) and run the machine service regression suites.
- [ ] ✅ Test passes + decision documented (green phase)

**Estimated Effort:** 1-2 hours

### Test: DH-P1-03 (P1) — EXPLAIN plan evidence

**File:** `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] Activate + run: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="DbIndexHygieneAtddGapScaffoldTest#explainPlanShowsOrderByIndexUsage"`
- [ ] Capture the two EXPLAIN plans. If the planner does NOT use `idx_machines_code` for the scoped query (likely on tiny fixtures), record it as evidence for the future composite `(plant_id, code)` tuning item in `deferred-work.md`.
- [ ] The scaffold asserts plans are captured (non-blank); the evidence text is the deliverable, not a hard index-usage threshold.
- [ ] ✅ Test passes + evidence recorded (green phase)

**Estimated Effort:** 1-2 hours

### Test: DH-P2-02 (P2) — exact-list assertion strengthening

**File:** `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] Activate + run: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="DbIndexHygieneAtddGapScaffoldTest#exactOrderingListAfterV17"`
- [ ] Expected green (seed is the only data in the container DB). Any red is a real ordering regression.
- [ ] ✅ Test passes (green phase)

**Estimated Effort:** 0.5 hour

### Test: T-DH-P2-01 (P2) — static unreferenced-objects grep (static guard)

**File:** none (repo-wide static check)

**Tasks to complete this item:**

- [ ] Run: `rg -n "idx_machine_sparepart_installations_machine_id_sparepart_id|uq_auth_user_plant_assignments_auth_user_plant|idx_auth_user_plant_assignments_auth_user_id|idx_machine_groups_plant_id|idx_machines_plant_id|idx_sparepart_taxonomy_dimension" syncro --glob '!**/V17__*.sql'`
- [ ] Confirm the only references are in `V17` and the dropped-object catalog test assertions (no app code / tests / seeds / other migrations depend on the dropped objects).
- [ ] ✅ No stale references (guard satisfied)

**Estimated Effort:** 0.5 hour

---

## Running Tests

```bash
# Backend — activate one at a time, run the specific scaffold class
$env:JAVA_HOME="C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
mvn -q -f syncro/apps/backend/pom.xml test -Dtest="DbIndexHygieneAtddUpgradePathScaffoldTest"
mvn -q -f syncro/apps/backend/pom.xml test -Dtest="DbIndexHygieneAtddGapScaffoldTest"

# Existing P0 + regression suites
mvn -q -f syncro/apps/backend/pom.xml test -Dtest="DbIndexHygieneMigrationTest"
mvn -q -f syncro/apps/backend/pom.xml test -Dtest="MachineServiceIntegrationTest,SparepartServiceIntegrationTest,SparepartTaxonomyServiceIntegrationTest"

# Static guard (T-DH-P2-01)
rg -n "idx_machine_sparepart_installations_machine_id_sparepart_id|uq_auth_user_plant_assignments_auth_user_plant|idx_auth_user_plant_assignments_auth_user_id|idx_machine_groups_plant_id|idx_machines_plant_id|idx_sparepart_taxonomy_dimension" syncro --glob '!**/V17__*.sql'
```

---

## Red-Green-Refactor Workflow

### RED Phase (Complete) ✅

- ✅ All tests written as red-phase scaffolds: 1 standalone upgrade-path `@Disabled`, 3 `@SpringBootTest` integration `@Disabled`.
- ✅ Scaffolds assert EXPECTED behavior (drops/creates/kept-objects/data-counts, exact lists, EXPLAIN capture) — no placeholder assertions.
- ✅ Verified compile: `mvn test-compile` (JDK 25) succeeds, both classes produced.

### GREEN Phase (DEV Team - Next Steps)

1. Pick one scaffolded test from the implementation checklist (start with the flagship `DH-P1-01` upgrade path).
2. Remove `@Disabled` for that test and confirm it runs (red if it fails, green if already satisfied).
3. For `DH-P1-02`/`DH-P1-03`: make + record the documented decisions (tiebreaker / EXPLAIN evidence).
4. Check off the task; move to the next.

### REFACTOR Phase (DEV Team - After All Tests Pass)

1. Verify all activated tests pass; review for quality.
2. Run the P0 + three service regression suites (`DbIndexHygieneMigrationTest`, `MachineServiceIntegrationTest`, `SparepartServiceIntegrationTest`, `SparepartTaxonomyServiceIntegrationTest`).
3. Update `test-design-story-db-index-hygiene.md` risk statuses (DH-07, DH-04, DH-02, DH-06).
4. Record the composite `(plant_id, code)` tuning item and the `SHARE`-lock maintenance window (DH-03) in `deferred-work.md` (or the ops runbook).
5. When all activated tests pass, manually update story status in `sprint-status.yaml`.

---

## Notes

- **Flagship RED:** `DH-P1-01` (DH-07) is the only *behavioral* gap — the upgrade path is the project rule the shipped test does not cover. The other three scaffolds are decision/evidence/activation locks; each is expected green today but documents a decision or strengthens an assertion (DH-04, DH-02, DH-06).
- **Activation-lock vs behavior-lock:** `DH-P2-02` is an activation lock (strict `containsExactly`); `DH-P1-02` and `DH-P1-03` are decision/evidence locks whose deliverable is the recorded decision + EXPLAIN plan text.
- Existing evidence already covers AC1-AC4 (`DbIndexHygieneMigrationTest` 6 tests, all passing). This run adds the uncovered-gap surface.
- **Ops note (DH-03):** V17's non-`CONCURRENTLY` `CREATE INDEX` takes a `SHARE` lock that blocks writes on each indexed table during build — a maintenance-window concern at production apply on hot/large tables. `CONCURRENTLY` not required at current table scale.
- **TECH note (DH-05):** the upgrade-path scaffold demonstrates the lightweight Flyway+JDBC Testcontainers harness as an alternative to `@SpringBootTest` for future schema tests. The `JwtTokenService` gitignore clean-checkout compile blocker is tracked separately, not this story's scope.

---

## Knowledge Base References Applied

- **test-levels-framework** - Backend Integration (DB constraints/migration) as the correct level for a schema-only chore.
- **test-priorities-matrix / risk-governance** - P1-P3 ordering driven by the story risk register (DH-07 upgrade path first).
- **test-quality** - Given-When-Then, one assertion intent per test, deterministic data, exact-list assertions over weak subsequence checks.
- **api-testing-patterns** - Testcontainers + `JdbcTemplate` + repository pattern mirrored from the shipped migration test.

---

## Contact

- Tag @TEA in team standup
- Consult `./resources/knowledge` for testing best practices

---

**Generated by BMad TEA Agent** - 2026-08-08
