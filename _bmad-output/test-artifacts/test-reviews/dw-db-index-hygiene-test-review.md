---
stepsCompleted: ['step-01-load-context', 'step-02-discover-tests', 'step-03-quality-evaluation', 'step-03f-aggregate-scores', 'step-04-generate-report']
lastStep: 'step-04-generate-report'
lastSaved: '2026-08-08'
workflowType: 'testarch-test-review'
reviewMode: 'create'
inputDocuments:
  - syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java
  - syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java
  - syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddUpgradePathScaffoldTest.java
  - syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneTestData.java
---

# Test Quality Review: dw-db-index-hygiene

**Quality Score**: 100/100 (A - Strong)
**Review Date**: 2026-08-08
**Review Scope**: directory (tests covering the V17 index-hygiene change in the working tree)
**Reviewer**: Murat / TEA Agent

---

`test-review` audits test quality only. Coverage mapping and gate decisions remain `trace` workflow scope.

## Executive Summary

**Overall Assessment**: Strong
**Recommendation**: Approve with Comments

The `dw-db-index-hygiene` test set is high quality and appropriately infrastructure-heavy. The four files reviewed exercise the V17 Flyway migration chain with real PostgreSQL Testcontainers, prove both the drop of six redundant indexes and the creation of three order-by-supporting indexes, verify an upgrade path from a seeded V16 schema, and prove ORDER BY / FK behavior end-to-end against the actual database — exactly the level required for migration, index, repository, and query-correctness evidence per project testing rules.

No CRITICAL or HIGH test-quality issue was found. Four LOW/MEDIUM rubric findings remain: the primary migration test duplicates payload construction inline instead of reusing the new `DbIndexHygieneTestData` factory, two `@DisplayName`s name repository methods rather than pure behavior, and one data-factory helper embeds unexplained magic literals.

**Context Basis**: none
**Context Waivers Applied**: 0

### Key Strengths

✅ Real PostgreSQL Testcontainers for all schema/index/repository/query behavior — no persistence mocking.
✅ `DbIndexHygieneAtddUpgradePathScaffoldTest` proves a clean V16 → V17 upgrade path with seeded data intact, covering both migration directions the project requires.
✅ ORDER BY behavior asserted against real query results (`containsExactly("M-001", "M-002")`, sorted taxonomy names), not just index metadata.
✅ FK enforcement (RESTRICT) proven after the index drops via a real `DataIntegrityViolationException`.
✅ `[P1]`/`[P2]` priority markers and behavior-stating display names in the ATDD scaffolds.

### Key Weaknesses

❌ `DbIndexHygieneMigrationTest` constructs the same domain payload shapes inline (3+ times) instead of reusing the `DbIndexHygieneTestData` factory it ships alongside.
❌ Two `@DisplayName` values in the migration test name repository methods (`findByDimensionOrderByNameAsc`, `findAllScoped/findAllUnscoped`) while the repo's behavioral-naming convention is established.
❌ The `explainPlanShowsOrderByIndexUsage` test asserts the plan is non-blank but never asserts the ORDER BY index is actually used.
❌ `DbIndexHygieneTestData` embeds magic literals (`10, 0, 90`, `'x'` password hash, `NOW()`) with no named constants or comments.

### Summary

This is a well-built integration test suite for a migration story. The dominant pattern — real database, transactional rollback, factory-seeded data, and behavior-level assertions — matches TEA's Definition of Done. The findings are all maintainability-level: favor the factory that already exists in the same package, tighten display-name phrasing to behavior, and strengthen the EXPLAIN assertion so it actually proves index usage. Nothing here blocks the change from being merged.

---

## Quality Criteria Assessment

| Criterion                            | Status                                           | Violations | Basis                                                     | Notes                                                                                       |
| ------------------------------------ | ------------------------------------------------ | ---------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| BDD Format (Given-When-Then)         | ⚠️ WARN                                          | 2          | Convention: `bddNaming` (19 of 31)                        | Two display names name repository methods instead of behavior.                              |
| Test IDs                             | ✅ PASS (n/a)                                    | 0          | Convention: `testIds` absent (0 of 31)                    | No test-id convention exists in the Java corpus.                                            |
| Priority Markers (P0/P1/P2/P3)       | ✅ PASS (n/a)                                    | 0          | Convention: `priorityMarkers` absent (0 of 31)            | No priority-marker convention in the corpus; ATDD scaffolds use `[P1]`/`[P2]` anyway.        |
| Disabled or Focused Tests            | ✅ PASS                                          | 0          | Absolute                                                  | No `.skip`, `@Ignore`, `.only`, or focused tests.                                           |
| Hard Waits (sleep, waitForTimeout)   | ✅ PASS                                          | 0          | Absolute                                                  | No sleeps, timers, or bare waits.                                                           |
| Determinism (no conditionals)        | ✅ PASS                                          | 0          | Absolute + Applicability                                  | No conditional assertions, wall-clock boundaries, or tautologies.                           |
| Isolation (cleanup, no shared state) | ✅ PASS                                          | 0          | Absolute                                                  | `@Transactional` rollback; static container read-only; no shared mutable state.             |
| Fixture Patterns                     | ⚠️ WARN                                          | 1          | Applicability: constructs domain payloads                 | Migration test bypasses the `DbIndexHygieneTestData` factory (M2).                          |
| Data Factories                       | ⚠️ WARN                                          | 1          | Applicability: constructs domain payloads                 | Same M2: factory exists in repo, primary test bypasses it.                                  |
| Network-First Pattern                | ✅ PASS (n/a)                                    | 0          | Applicability: file never navigates                       | Backend integration tests; no navigation/interception.                                     |
| Explicit Assertions                  | ✅ PASS                                          | 0          | Absolute                                                  | Every test asserts against real behavior; no tautologies or no-op assertions.               |
| Test Length (≤300 lines)             | ✅ PASS                                          | 210 max   | Absolute                                                  | Largest reviewed file is 210 lines (migration test).                                       |
| Test Duration (≤1.5 min)             | ✅ PASS                                          | n/a       | Absolute                                                  | No excessive loops/sleeps/navigation; Testcontainers cost is justified.                    |
| Flakiness Patterns                   | ✅ PASS                                          | 0          | Absolute + Applicability                                  | No timers, wall clocks, shared state, or ordering dependence.                               |

**Total Violations**: 0 Critical, 0 High, 1 Medium, 3 Low

**Convention Baseline**: 31 test files sampled outside the review set.

---

## Quality Score Breakdown

```
Starting Score:          100
Critical Violations:     -0 × 10 = -0
High Violations:         -0 × 5 = -0
Medium Violations:       -1 × 2 = -2
Low Violations:          -3 × 1 = -3

Bonus Points:
  Excellent BDD:         +0
  Comprehensive Fixtures: +0
  Data Factories:        +0
  Network-First:         +0
  Perfect Isolation:     +5
  All Test IDs:          +0
                         --------
Total Bonus:             +5

Final Score:             100/100
Grade:                   A
```

---

## Critical Issues (Must Fix)

No critical issues detected. ✅

---

## Recommendations (Should Fix)

### 1. Reuse the existing data factory instead of inline SQL payloads

**Severity**: P2 (Medium)
**Location**: `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java:128`
**Row**: M2
**Criterion**: Fixture Patterns / Data Factories
**Knowledge Base**: [data-factories.md](../../../agents/bmad-tea/resources/knowledge/data-factories.md)

**Issue Description**:
The migration test constructs the same domain payload shapes inline via raw `jdbc.update` three or more times (`INSERT INTO sparepart_taxonomy ... VALUES (?, 'CATEGORY', ...)` at lines 128/130/132; `INSERT INTO machines ...` at lines 156/159/189), while a factory for exactly those shapes — `DbIndexHygieneTestData.plant/machineGroup/machine/taxonomy` — exists in the same package. The file ships alongside the factory and bypasses it, so payload construction is duplicated in two places.

**Current Code**:

```java
// ⚠️ Could be improved (current implementation)
jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name) VALUES (?, 'CATEGORY', 'C-02', 'Beta')", UUID.randomUUID());
jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name) VALUES (?, 'CATEGORY', 'C-01', 'Alpha')", UUID.randomUUID());
jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name) VALUES (?, 'CATEGORY', 'C-03', 'Charlie')", UUID.randomUUID());
```

**Recommended Improvement**:

```java
// ✅ Better approach
DbIndexHygieneTestData.taxonomy(jdbc, "CATEGORY", "C-02", "Beta");
DbIndexHygieneTestData.taxonomy(jdbc, "CATEGORY", "C-01", "Alpha");
DbIndexHygieneTestData.taxonomy(jdbc, "CATEGORY", "C-03", "Charlie");
```

**Benefits**: Single source of truth for seed data shapes; new columns or constraints are updated once; the upgrade-path test and the migration test cannot drift.

**Priority**: P2 — the duplication is real and the factory already exists; it erodes as more tests are added.

### 2. Name display names by behavior, not by repository method

**Severity**: P3 (Low)
**Location**: `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java:126,146`
**Row**: L5
**Criterion**: BDD Format
**Knowledge Base**: [test-quality.md](../../../agents/bmad-tea/resources/knowledge/test-quality.md)

**Issue Description**:
The repo's behavioral-naming convention is established (19 of 31 corpus files phrase `@DisplayName` as behavior). Two migration-test display names instead name the repository method first: `"ORDER_PRESERVED: findByDimensionOrderByNameAsc returns rows ordered by name after V17"` and `"JOIN_STILL_OK: findAllScoped/findAllUnscoped return machines ordered by code after V17"`. If the method is renamed, the display name lies.

**Current Code**:

```java
// ⚠️ Could be improved (current implementation)
@DisplayName("ORDER_PRESERVED: findByDimensionOrderByNameAsc returns rows ordered by name after V17")
@DisplayName("JOIN_STILL_OK: findAllScoped/findAllUnscoped return machines ordered by code after V17")
```

**Recommended Improvement**:

```java
// ✅ Better approach
@DisplayName("Taxonomy rows stay ordered by name after the V17 index drops")
@DisplayName("Machines stay ordered by code in both scoped and unscoped lists after the V17 index drops")
```

**Benefits**: Names survive refactors, read as acceptance criteria, and match the corpus convention.

**Priority**: P3 — cosmetic today, but it is drift against a measured house convention.

### 3. Explain the magic literals in the data factory

**Severity**: P3 (Low)
**Location**: `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneTestData.java:64`
**Row**: L6
**Criterion**: Maintainability
**Knowledge Base**: [test-quality.md](../../../agents/bmad-tea/resources/knowledge/test-quality.md)

**Issue Description**:
`installation(...)` hardcodes `10, 0, 90` (expected production count, baseline counter, threshold percentage) and `NOW()`; `authUser(...)` hardcodes `'x'` as the password hash. These carry domain meaning (90 is the canonical 90% threshold) with no name or comment.

**Current Code**:

```java
// ⚠️ Could be improved (current implementation)
" VALUES (?, ?, ?, 10, 0, 90, NOW())"
```

**Recommended Improvement**:

```java
// ✅ Better approach
" VALUES (?, ?, ?, 10, 0, 90, NOW())"
// or extract named constants:
static final int DEFAULT_EXPECTED_PRODUCTION_COUNT = 10;
static final int DEFAULT_THRESHOLD_PERCENTAGE = 90;
```

**Benefits**: The 90% threshold and placeholder hash stop looking like arbitrary numbers; reviewers and future authors know the intent.

**Priority**: P3 — minor readability improvement in a support helper.

### 4. Strengthen the EXPLAIN assertion to prove index usage

**Severity**: Prose (no registry row — no deduction)
**Location**: `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java:116-122`
**Row**: none — the registry has no row for a weak-but-present assertion
**Criterion**: n/a

**Issue Description**:
`explainPlanShowsOrderByIndexUsage` captures `EXPLAIN (FORMAT TEXT)` plans but asserts only `isNotBlank()`. The plan would pass even if PostgreSQL chose a sequential scan and ignored the ORDER BY index — so the test name ("EXPLAIN captures plan evidence for ORDER BY index usage") overstates what is verified. (Reported in prose: no registry row covers this, so no severity and no deduction.)

**Recommended Improvement**:

```java
assertThat(scopedPlan).contains("Index Scan");
assertThat(unscopedPlan).contains("Index Scan");
// and ideally assert the index name: "using idx_machines_code"
```

**Benefits**: The test actually fails if the ORDER BY index is not used — which is the behavior the story exists to guarantee.

**Priority**: Follow-up hardening; the ordering behavior itself is already proven by the real-result assertions in the same suite.

---

## Best Practices Found

### 1. Real database for migration and index correctness

**Location**: `DbIndexHygieneMigrationTest.java:58-59`, `DbIndexHygieneAtddUpgradePathScaffoldTest.java:18-19`
**Pattern**: PostgreSQL Testcontainers for schema/index/repository/query behavior
**Knowledge Base**: [test-quality.md](../../../agents/bmad-tea/resources/knowledge/test-quality.md)

**Why This Is Good**: Migration, index, constraint, ordering, and FK evidence requires a real database — exactly what project rules mandate ("Never mock persistence for migration, repository, transaction, locking, uniqueness, constraints, indexes, or query correctness"). Both test classes use `postgres:17-alpine` and mapped container ports.

### 2. Upgrade path proven with seeded data

**Location**: `DbIndexHygieneAtddUpgradePathScaffoldTest.java:23-66`
**Pattern**: Flyway `target(MigrationVersion.fromVersion("16"))` → seed → migrate to `"17"` → assert data intact and schema changed
**Knowledge Base**: [test-levels-framework.md](../../../agents/bmad-tea/resources/knowledge/test-levels-framework.md)

**Why This Is Good**: It covers the "apply over previous schema state" direction the project explicitly requires, and asserts seeded row counts survive the migration — not just index metadata.

### 3. Behavior-level ordering assertions

**Location**: `DbIndexHygieneMigrationTest.java:141,169,177`
**Pattern**: Assert real query results (`isSorted()`, `containsExactly("M-001", "M-002")`), not index metadata alone
**Knowledge Base**: [test-quality.md](../../../agents/bmad-tea/resources/knowledge/test-quality.md)

**Why This Is Good**: ORDER BY behavior is proven end-to-end through the repository, which is the user-visible guarantee the ORDER BY indexes exist to serve.

### 4. Transactional isolation per test

**Location**: `DbIndexHygieneMigrationTest.java:125,145,181`
**Pattern**: `@Transactional` rollback so each test's seed data does not leak into the next
**Knowledge Base**: [test-quality.md](../../../agents/bmad-tea/resources/knowledge/test-quality.md)

**Why This Is Good**: Tests can run alone or in any order without cross-test contamination — the basis for the Perfect Isolation bonus.

### 5. Factory-backed seed data in the ATDD scaffolds

**Location**: `DbIndexHygieneAtddGapScaffoldTest.java:80-86`, `DbIndexHygieneAtddUpgradePathScaffoldTest.java:27-38`
**Pattern**: `DbIndexHygieneTestData.plant/machineGroup/machine/taxonomy/sparepart/...`
**Knowledge Base**: [data-factories.md](../../../agents/bmad-tea/resources/knowledge/data-factories.md)

**Why This Is Good**: The scaffolds centralize payload construction through a named factory with explicit column lists, keeping the tests readable and the schema knowledge in one place.

---

## Test File Analysis

### DbIndexHygieneMigrationTest.java

- **File Path**: `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java`
- **File Size**: 210 lines
- **Test Framework**: JUnit 5 + AssertJ + Testcontainers (Spring Boot integration)
- **Describe Blocks**: 0 (class-level; corpus convention, see M4 note)
- **Test Cases (test)**: 7
- **Assertions**: explicit AssertJ `assertThat` throughout; includes `assertThatThrownBy` for FK RESTRICT.
- **Notable**: `@Transactional` rollback on all data-mutating tests; `to_regclass` + `pg_indexes` queries for index evidence.

### DbIndexHygieneAtddGapScaffoldTest.java

- **File Path**: `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java`
- **File Size**: 147 lines
- **Test Framework**: JUnit 5 + AssertJ + Testcontainers
- **Test Cases (test)**: 3
- **Notable**: `[P1]`/`[P2]` priority markers; duplicate-code pagination proof; EXPLAIN capture. Uses the factory.

### DbIndexHygieneAtddUpgradePathScaffoldTest.java

- **File Path**: `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddUpgradePathScaffoldTest.java`
- **File Size**: 81 lines
- **Test Framework**: JUnit 5 + AssertJ + Testcontainers (raw Flyway, no Spring context)
- **Test Cases (test)**: 1 (comprehensive upgrade-path scenario)
- **Notable**: Seeds a full V16 graph, migrates to V17, asserts drops, creations, row survival, and kept constraints.

### DbIndexHygieneTestData.java

- **File Path**: `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneTestData.java`
- **File Size**: 92 lines
- **Type**: Test data factory (no test methods)
- **Notable**: Centralizes `plant/machineGroup/machine/taxonomy/sparepart/installation/authUser/plantAssignment` inserts plus `exists`/`indexDef` helpers.

---

## Context and Integration

### What the Context Said

No context files were supplied to this run (`context_basis: none`), so nothing here checked the tests against a story or test design; the verdict speaks to how the tests are built. The review set was resolved from the working-tree change: the four `com.syncro.db` test artifacts added/committed for the V17 index-hygiene story.

### Related Artifacts

None supplied to this run. Related artifacts for this story (spec, test design, traceability) are produced by other workflows in the module.

---

## Knowledge Base References

This review consulted the following knowledge base fragments:

- **[test-quality.md](../../../agents/bmad-tea/resources/knowledge/test-quality.md)** - Definition of Done for tests (no hard waits, <300 lines, <1.5 min, self-cleaning)
- **[data-factories.md](../../../agents/bmad-tea/resources/knowledge/data-factories.md)** - Factory functions with overrides, API-first setup
- **[test-levels-framework.md](../../../agents/bmad-tea/resources/knowledge/test-levels-framework.md)** - E2E vs API vs Component vs Unit appropriateness
- **[selective-testing.md](../../../agents/bmad-tea/resources/knowledge/selective-testing.md)** - Duplicate coverage detection

For coverage mapping, consult `trace` workflow outputs.

---

## Next Steps

### Immediate Actions (Before Merge)

1. **Reuse `DbIndexHygieneTestData` in the migration test** - Replace the inline 3+ repeated payload inserts with factory calls.
   - Priority: P2
   - Owner: Backend / Story owner
   - Estimated Effort: ~15 min

### Follow-up Actions (Future PRs)

1. **Strengthen the EXPLAIN assertion** - Assert the plan shows `Index Scan` on `idx_machines_code` / `idx_sparepart_taxonomy_dimension_name`.
   - Priority: P2
   - Target: next story touching query performance

2. **Name display names by behavior** - Refactor the two method-naming `@DisplayName`s.
   - Priority: P3
   - Target: backlog

3. **Extract named constants in `DbIndexHygieneTestData`** - Name the 90% threshold and placeholder hash.
   - Priority: P3
   - Target: backlog

### Re-Review Needed?

⚠️ Re-review after fixes - approve with comments; the two P2 items (factory reuse, EXPLAIN strength) are worth a quick pass.

---

## Decision

**Recommendation**: Approve with Comments

**Rationale**:
The suite is infrastructure-correct: every migration, index, constraint, ordering, and FK behavior is proven against a real PostgreSQL 17 database, with transactional isolation and a verified V16→V17 upgrade path. No CRITICAL or HIGH rubric row fired, and the Perfect Isolation bonus is earned across all four files (no shared mutable state; any test runs alone or in parallel).

The four findings are maintainability-grade. The migration test bypassing the factory it ships with (M2, MEDIUM) is the most substantive and is a one-line-per-call fix. The two L5 display-name and one L6 magic-literal findings are cosmetic but represent real drift against measured house conventions. None of these justifies blocking the change.

**For Approve with Comments**:

> Test quality is strong with 100/100 score. The primary migration test should reuse the existing `DbIndexHygieneTestData` factory, the EXPLAIN assertion should prove index usage rather than a non-blank plan, and display names should phrase behavior rather than method names. Critical issues resolved; these improvements would enhance maintainability.

---

## Appendix

### Violation Summary by Location

| Line  | Severity | Criterion   | Issue                                                            | Fix                                                          |
| ----- | -------- | ----------- | ---------------------------------------------------------------- | ------------------------------------------------------------ |
| 128   | P2       | M2          | Repeated inline payload construction bypasses factory            | Use `DbIndexHygieneTestData.taxonomy/machine/plant`          |
| 126   | P3       | L5          | Display name names repository method                             | Phrase as behavior                                           |
| 146   | P3       | L5          | Display name names repository methods                            | Phrase as behavior                                           |
| 64    | P3       | L6          | Magic literals (10/0/90, 'x', NOW()) with no names/comments     | Extract named constants                                      |

### Quality Trends

| Review Date  | Score         | Grade     | Critical Issues | Trend       |
| ------------ | ------------- | --------- | --------------- | ----------- |
| 2026-08-08   | 100/100       | A         | 0               | Baseline    |

### Related Reviews

| File     | Score       | Grade   | Critical | Status             |
| -------- | ----------- | ------- | -------- | ------------------ |
| (single suite review — all four files scored together) | 100/100 | A | 0 | Approved with Comments |

**Suite Average**: 100/100 (A)

---

## Review Metadata

**Generated By**: BMad TEA Agent (Test Architect)
**Workflow**: testarch-test-review v5.0
**Review ID**: test-review-dw-db-index-hygiene-20260808
**Timestamp**: 2026-08-08
**Version**: 1.0

---

## Reviewed Files

- syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java
- syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddGapScaffoldTest.java
- syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneAtddUpgradePathScaffoldTest.java
- syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneTestData.java

## Review Context

- none
