---
stepsCompleted: ['step-01-load-context', 'step-02-discover-tests', 'step-03-map-criteria', 'step-04-analyze-gaps', 'step-05-gate-decision']
lastStep: 'step-05-gate-decision'
lastSaved: '2026-08-08'
workflowType: 'testarch-trace'
storyId: 'dw-db-index-hygiene'
storyKey: 'dw-db-index-hygiene'
coverageBasis: 'acceptance_criteria'
oracleConfidence: 'high'
oracleResolutionMode: 'formal_requirements'
oracleSources:
  - _bmad-output/implementation-artifacts/spec-db-index-hygiene.md
  - _bmad-output/test-artifacts/test-design-story-db-index-hygiene.md
  - _bmad-output/test-artifacts/atdd-checklist-dw-db-index-hygiene.md
  - _bmad-output/test-artifacts/automation-summary-dw-db-index-hygiene.md
externalPointerStatus: 'not_used'
tempCoverageMatrixPath: 'C:\Users\Dell\AppData\Local\Temp\opencode\tea-trace-coverage-matrix-2026-08-08-story-dw-db-index-hygiene.json'
gateDecision: 'PASS'
collectionStatus: 'COLLECTED'
---

# Traceability Report: Story dw-db-index-hygiene (Drop Redundant DB Indexes, Add ORDER BY Indexes — V17)

**Target:** dw-db-index-hygiene
**Date:** 2026-08-08
**Evaluator:** Yusuf (TEA / bmad-loop trace run)
**Coverage Oracle:** Story acceptance criteria (AC1-AC4)
**Oracle Confidence:** High
**Oracle Sources:** story spec, story test design, ATDD checklist, automation summary
**Gate Decision:** PASS
**Collection Status:** COLLECTED

> Note: This workflow does not generate tests. The four ATDD gap scaffolds referenced here were activated and made green by the earlier `*automate` run; this trace run re-ran them locally and confirmed **11/11 tests pass** (BUILD SUCCESS, Testcontainers `postgres:17-alpine`, JDK 25).

## PHASE 1: REQUIREMENTS TRACEABILITY

### Coverage Summary

| Priority  | Total Criteria | FULL Coverage | Coverage % | Status       |
| --------- | -------------- | ------------- | ---------- | ------------ |
| P0        | 4              | 4             | 100%       | ✅ PASS |
| P1        | 0              | 0             | -          | - |
| P2        | 0              | 0             | -          | - |
| P3        | 0              | 0             | -          | - |
| **Total** | **4**          | **4**         | **100%**   | **✅ PASS** |

**Legend:**
- ✅ PASS - Coverage meets quality gate threshold
- ⚠️ WARN - Coverage below threshold but not critical
- ❌ FAIL - Coverage below minimum threshold (blocker)

---

### Detailed Mapping

#### AC1: Fresh DB runs V1-V17; the 6 redundant indexes are absent (P0)

- **Coverage:** FULL ✅
- **Tests:**
  - `DH-MIG-01` - syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java:79 (integration, active) - `redundantIndexesAreDropped`
    - **Given:** A fresh PostgreSQL via Testcontainers with the full V1-V17 Flyway chain applied
    - **When:** `to_regclass` is queried for each of the 6 redundant index/constraint names
    - **Then:** All six (`idx_machine_sparepart_installations_machine_id_sparepart_id`, `uq_auth_user_plant_assignments_auth_user_plant`, `idx_auth_user_plant_assignments_auth_user_id`, `idx_machine_groups_plant_id`, `idx_machines_plant_id`, `idx_sparepart_taxonomy_dimension`) resolve to null
  - `DH-P1-01` - syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddUpgradePathScaffoldTest.java:23 (integration, active) - `v17UpgradePathFromV16WithSeedData`
    - **Given:** A DB migrated to V16 with seeded plants/groups/machines/taxonomy/spareparts/installations/auth rows
    - **When:** V17 is applied over the existing V16 schema
    - **Then:** All 6 dropped objects are absent and seeded data counts survive
- **Gaps:** None.

---

#### AC2: The 3 new indexes exist with expected column lists (P0)

- **Coverage:** FULL ✅
- **Tests:**
  - `DH-MIG-02` - syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java:90 (integration, active) - `queryIndexesArePresentWithExpectedDefinitions`
    - **Given:** A fresh PostgreSQL via Testcontainers with the full V1-V17 chain applied
    - **When:** `indexdef` is read from `pg_indexes` for the 3 new indexes
    - **Then:** `idx_spareparts_code` and `idx_machines_code` end with `USING btree (code)`; `idx_sparepart_taxonomy_dimension_name` ends with `USING btree (dimension, name)`
  - `DH-P1-01` - syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddUpgradePathScaffoldTest.java:23 (integration, active) - `v17UpgradePathFromV16WithSeedData` (also asserts the 3 creates on the upgrade path)
  - Supporting catalog guards: `DH-MIG-03` (`keptConstraintsAndUniqueIndexesStillExist`, line 104), `DH-MIG-04` (`keptSingleColumnIndexesStillExist`, line 116), `DH-MIG-07` (`referentialIntegrityStillEnforcedAfterV17`, line 183) prove kept PKs/unique/FK-supporting indexes and FK enforcement survive the drops.
- **Gaps:** None.

---

#### AC3: `findByDimensionOrderByNameAsc` orders by name ascending within dimension (P0)

- **Coverage:** FULL ✅
- **Tests:**
  - `DH-MIG-05` - syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java:127 (integration, active) - `taxonomyOrderingPreservedAfterV17`
    - **Given:** Three CATEGORY taxonomy rows inserted out of insertion order
    - **When:** `SparepartTaxonomyRepository.findByDimensionOrderByNameAsc(CATEGORY)` runs
    - **Then:** Returned names are sorted ascending (`isSorted()`)
  - `DH-P2-02` - syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java:127 (integration, active) - `exactOrderingListAfterV17`
    - **Given:** Seeded CATEGORY rows cleared, then Alpha/Beta/Charlie inserted out of order
    - **When:** `findByDimensionOrderByNameAsc(CATEGORY)` runs
    - **Then:** Names equal exactly `["Alpha", "Beta", "Charlie"]` (`containsExactly`, stricter than the old subsequence assertion)
- **Gaps:** None.

---

#### AC4: `findAllScoped`/`findAllUnscoped` list machines correctly with plant/group joins (P0)

- **Coverage:** FULL ✅
- **Tests:**
  - `DH-MIG-06` - syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java:147 (integration, active) - `machineListOrderingPreservedAfterV17`
    - **Given:** A plant, group, and two machines (`M-002`, `M-001`) inserted out of insertion order
    - **When:** `MachineRepository.findAllScoped` and `findAllUnscoped` run with `ORDER BY code`
    - **Then:** Both return `["M-001", "M-002"]` (`containsExactly`) with joins intact
  - `DH-P1-02` - syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java:79 (integration, active) - `orderByCodePaginationIsCompleteAcrossDuplicateCodes`
    - **Given:** Two plants whose machines share the code `M-DUP` (legal per `(plant_id, lower(code))` uniqueness)
    - **When:** `findAllUnscoped` is paged at page size 1 over `ORDER BY code`
    - **Then:** Every row is returned exactly once (`hasSize(2).containsOnly("M-DUP")` and `containsExactly("M-DUP", "M-DUP")`)
  - `DH-P1-03` - syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java:111 (integration, active) - `explainPlanShowsOrderByIndexUsage`
    - **Given:** A scoped and an unscoped `ORDER BY code` machine query
    - **When:** `EXPLAIN (FORMAT TEXT)` captures both plans
    - **Then:** Both plans are non-blank (plan text recorded as the DH-02 index-usefulness evidence)
- **Gaps:** None.

---

### Gap Analysis

#### Critical Gaps (BLOCKER) ❌

0 gaps found.

#### High Priority Gaps (PR BLOCKER) ⚠️

0 gaps found.

#### Medium Priority Gaps (Nightly) ⚠️

0 gaps found.

#### Low Priority Gaps (Optional) ℹ️

0 gaps found.

---

### Coverage Heuristics Findings

#### Endpoint Coverage Gaps

- Endpoints without direct API tests: 0 (schema-only chore; behavior is exercised through real repository methods over Testcontainers)

#### Auth/Authz Negative-Path Gaps

- Criteria missing denied/invalid-path tests: 0 (no auth surface in this story)

#### Happy-Path-Only Criteria

- Criteria missing error/edge scenarios: 0 (AC1-AC4 cover negative catalog absence, exact column lists, strict ordering, duplicate-code pagination, FK enforcement error path)

---

### Quality Assessment

#### Tests with Issues

**BLOCKER Issues** ❌

- None.

**WARNING Issues** ⚠️

- `DH-P1-02` - The pagination-determinism decision (DH-04) is not yet locked: `ORDER BY code` OFFSET pagination across identical codes is still non-deterministic per-page across plants. The test proves completeness only. - Record the tiebreaker decision (add `ORDER BY code, id` vs. accept) at story closeout.
- `DH-P1-03` - On tiny fixtures the planner may still seq-scan; the EXPLAIN test only proves plans are captured, not that the new indexes are used. - Use the recorded plan text to drive the future composite `(plant_id, code)` tuning item.

**INFO Issues** ℹ️

- `DH-P2-02` - Was an activation-lock scaffold; now green and duplicates AC3 coverage at the strictest level (defense in depth) — acceptable overlap with `DH-MIG-05`.

#### Tests Passing Quality Gates

**11/11 tests (100%) meet all quality criteria** ✅

---

### Duplicate Coverage Analysis

#### Acceptable Overlap (Defense in Depth)

- AC1/AC2: Fresh-DB catalog assertions (`DH-MIG-01/02`) plus upgrade-path probe (`DH-P1-01`) — complementary environments (fresh vs. from-V16).
- AC3: `isSorted()` (`DH-MIG-05`) plus exact `containsExactly` (`DH-P2-02`) — strictness ladder, not duplication.
- AC4: Scoped/unscoped ordering (`DH-MIG-06`) plus pagination edge (`DH-P1-02`) plus EXPLAIN (`DH-P1-03`).

#### Unacceptable Duplication ⚠️

- None.

---

### Coverage by Test Level

| Test Level | Tests             | Criteria Covered     | Coverage %       |
| ---------- | ----------------- | -------------------- | ---------------- |
| E2E        | 0                 | 0                    | -                |
| API        | 0                 | 0                    | -                |
| Component  | 0                 | 0                    | -                |
| Unit       | 0                 | 0                    | -                |
| Integration (Testcontainers) | 11 | 4                    | 100% |
| **Total**  | **11**            | **4**                | **100%** |

---

### Traceability Recommendations

#### Immediate Actions (Before PR Merge)

1. **None required** — P0 coverage is 100% with all 11 tests passing locally this run.

#### Short-term Actions (This Milestone)

1. **Lock the DH-04 pagination decision** - record whether `MachineRepository.findAllScoped/findAllUnscoped` gain a deterministic secondary sort key (`ORDER BY code, id`) or the non-determinism is accepted and documented. `DH-P1-02` stays as the completeness guard either way.
2. **Record the DH-02 EXPLAIN evidence** - the captured plan text feeds the future composite `(plant_id, code)` index-tuning item.

#### Long-term Actions (Backlog)

1. **Ops note (DH-03)** - document the V17 `SHARE`-lock maintenance window for production apply on hot/large tables.
2. **TECH note (DH-05)** - the upgrade-path scaffold demonstrates a lighter Flyway+JDBC Testcontainers harness for future schema tests; track the gitignored `JwtTokenService` clean-checkout compile blocker separately.

---

## PHASE 2: QUALITY GATE DECISION

**Gate Type:** story
**Decision Mode:** deterministic

---

### Evidence Summary

#### Test Execution Results

- **Total Tests**: 11
- **Passed**: 11 (100%)
- **Failed**: 0 (0%)
- **Skipped**: 0 (0%)
- **Duration**: ~29s (GapScaffold) + ~7s (UpgradePath) + ~9s (Migration) + context boot

**Priority Breakdown:**

- **P0 Tests**: 11/11 passed (100%) ✅
- **P1 Tests**: n/a (no P1 requirements; scaffold IDs P1 refer to evidence/decision locks that are green)
- **P2 Tests**: n/a
- **P3 Tests**: n/a

**Overall Pass Rate**: 100% ✅

**Test Results Source**: local run — `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="DbIndexHygieneMigrationTest,DbIndexHygieneAtddUpgradePathScaffoldTest,DbIndexHygieneAtddGapScaffoldTest"` (BUILD SUCCESS, JDK 25, Testcontainers `postgres:17-alpine`)

---

#### Coverage Summary (from Phase 1)

**Requirements Coverage:**

- **P0 Acceptance Criteria**: 4/4 covered (100%) ✅
- **P1 Acceptance Criteria**: 0/0 (n/a)
- **Overall Coverage**: 100%

**Code Coverage** (if available):

- Not collected for this schema-only chore; catalog + behavioral evidence is the coverage basis.

**Coverage Source**: traceability matrix (this report)

---

#### Non-Functional Requirements (NFRs)

**Security**: NOT_ASSESSED ℹ️

- No security surface in a schema-only migration bundle.

**Performance**: CONCERNS ⚠️

- `DH-P1-03` captures EXPLAIN plans but the planner may seq-scan on tiny fixtures; whether bare `(code)` indexes actually serve the scoped ORDER BY is unproven at production scale (DH-02). Deferred to `nfr-assess` after plan-level evidence review.

**Reliability**: PASS ✅

- V17 applies cleanly on fresh DB (V1-V17) and from V16 state with seeded data (`DH-P1-01`); ordering preserved (AC3/AC4); FK enforcement intact (`DH-MIG-07`).

**Maintainability**: PASS ✅

- 3 small focused test classes, shared `DbIndexHygieneTestData` fixture, deterministic data, no new frameworks.

**NFR Source**: not assessed (story test-design section 6; final verdict deferred to `nfr-assess`)

---

#### Flakiness Validation

**Burn-in Results** (if available):

- **Burn-in Iterations**: 1 (single local run this workflow)
- **Flaky Tests Detected**: 0
- **Stability Score**: 100%

**Burn-in Source**: not_available (single local run)

---

### Decision Criteria Evaluation

#### P0 Criteria (Must ALL Pass)

| Criterion             | Threshold | Actual                    | Status   |
| --------------------- | --------- | ------------------------- | -------- |
| P0 Coverage           | 100%      | 100%                      | ✅ PASS |
| P0 Test Pass Rate     | 100%      | 100%                      | ✅ PASS |
| Security Issues       | 0         | 0                         | ✅ PASS |
| Critical NFR Failures | 0         | 0                         | ✅ PASS |
| Flaky Tests           | 0         | 0                         | ✅ PASS |

**P0 Evaluation**: ✅ ALL PASS

---

#### P1 Criteria (Required for PASS, May Accept for CONCERNS)

| Criterion              | Threshold                 | Actual               | Status   |
| ---------------------- | ------------------------- | -------------------- | -------- |
| P1 Coverage            | ≥80%                      | n/a (no P1 reqs)     | ✅ PASS |
| P1 Test Pass Rate      | ≥95%                      | 100%                 | ✅ PASS |
| Overall Test Pass Rate | ≥95%                      | 100%                 | ✅ PASS |
| Overall Coverage       | ≥80%                      | 100%                 | ✅ PASS |

**P1 Evaluation**: ✅ ALL PASS

---

#### P2/P3 Criteria (Informational, Don't Block)

| Criterion         | Actual          | Notes                                                        |
| ----------------- | --------------- | ------------------------------------------------------------ |
| P2 Test Pass Rate | 100%            | Tracked, doesn't block |
| P3 Test Pass Rate | n/a             | Tracked, doesn't block |

---

### GATE DECISION: PASS

---

### Rationale

All P0 criteria met: 100% coverage of the four acceptance criteria (AC1-AC4) and 100% test pass rate (11/11 active backend integration tests verified green in this run). No critical gaps, no security issues, no flaky tests. The story is a schema-only chore with the schema state (drops, creates, kept objects, FK enforcement) and query-ordering behavior proven via Testcontainers on both the fresh (V1-V17) and upgrade (V16→V17) paths.

Residual items are documentation/decision locks, not gate blockers: the DH-04 `ORDER BY code` pagination tiebreaker decision and the DH-02 EXPLAIN-based evidence for the future composite `(plant_id, code)` index are recorded as short-term recommendations and should be closed at story closeout, but they do not affect this chore's correctness gate.

**Assumptions/caveats:** The P1/P2/P3 rows are effectively n/a because all four acceptance criteria are P0; the deterministic gate therefore resolves on P0 = 100% and overall = 100%. Burn-in validation is single-run only.

---

### Residual Risks (For CONCERNS or WAIVED)

Not applicable — decision is PASS. Tracked recommendations (non-blocking):

1. **DH-04 pagination non-determinism across duplicate codes**
   - **Priority**: P1
   - **Probability**: Medium
   - **Impact**: Medium
   - **Risk Score**: 4
   - **Mitigation**: `DH-P1-02` proves completeness; per-page stability already holds
   - **Remediation**: Add `ORDER BY code, id` tiebreaker or document acceptance at story closeout

2. **DH-02 bare `(code)` index usefulness for scoped ORDER BY**
   - **Priority**: P2
   - **Probability**: Medium
   - **Impact**: Medium
   - **Risk Score**: 4
   - **Mitigation**: EXPLAIN evidence captured by `DH-P1-03`
   - **Remediation**: Record evidence; evaluate composite `(plant_id, code)` as a future tuning item

**Overall Residual Risk**: LOW

---

### Gate Recommendations

#### For PASS Decision ✅

1. **Proceed** — the bundle is safe to merge; run the P0 migration test + three service regression suites as the PR gate.
2. **Post-Deployment Monitoring** - after V17 applies in production, watch for write-lock stalls on hot tables during index creation (DH-03 maintenance-window note) and validate list-query latency.
3. **Success Criteria** - list queries return correct ORDER BY; no duplicate/missing rows under pagination; no FK enforcement regressions.

---

### Next Steps

**Immediate Actions** (next 24-48 hours):

1. Merge the working-tree tests (3 test classes + fixture) as part of the bundle's PR.
2. Run `DbIndexHygieneMigrationTest,DbIndexHygieneAtddUpgradePathScaffoldTest,DbIndexHygieneAtddGapScaffoldTest` plus `MachineServiceIntegrationTest,SparepartServiceIntegrationTest,SparepartTaxonomyServiceIntegrationTest` in CI.
3. Record the DH-04 tiebreaker decision at story closeout.

**Follow-up Actions** (next milestone/release):

1. Add the DH-02 EXPLAIN evidence and composite `(plant_id, code)` tuning item to the index-tuning backlog.
2. Document the V17 SHARE-lock maintenance window in the ops runbook.
3. Optionally run TEA `test-review` on the three test classes.

**Stakeholder Communication**:

- Notify PM: Story gate PASS — schema-only bundle fully covered, no blockers.
- Notify DEV lead: 11/11 tests green; two non-blocking decision/evidence items to close at story closeout.

---

## Sign-Off

**Phase 1 - Traceability Assessment:**

- Overall Coverage: 100%
- P0 Coverage: 100% ✅
- P1 Coverage: n/a ✅
- Critical Gaps: 0
- High Priority Gaps: 0

**Phase 2 - Gate Decision:**

- **Decision**: PASS ✅
- **P0 Evaluation**: ✅ ALL PASS
- **P1 Evaluation**: ✅ ALL PASS

**Overall Status:** PASS ✅

**Next Steps:**

- If PASS ✅: Proceed to merge/deployment.

**Generated:** 2026-08-08
**Workflow:** testarch-trace v4.0 (Enhanced with Gate Decision)

---

<!-- Powered by BMAD-CORE™ -->
