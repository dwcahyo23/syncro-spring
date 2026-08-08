---
stepsCompleted: ['step-01-load-context', 'step-02-discover-tests', 'step-03-quality-evaluation', 'step-03a-subagent-determinism', 'step-03b-subagent-isolation', 'step-03c-subagent-maintainability', 'step-03e-subagent-performance', 'step-03f-aggregate-scores', 'step-04-generate-report']
lastStep: 'step-04-generate-report'
lastSaved: '2026-08-08'
workflowType: 'testarch-test-review'
inputDocuments:
  - _bmad-output/implementation-artifacts/spec-web-e2e-config-hardening.md
  - syncro/apps/web/tests/support/config.ts
  - syncro/apps/web/playwright.config.ts
  - syncro/apps/web/playwright.api.config.ts
  - syncro/apps/web/tests/support/check-api-collection.mjs
  - _bmad/tea/config.yaml
  - .claude/skills/bmad-testarch-test-review/resources/tea-index.csv
---

# Test Quality Review: dw-web-e2e-config-hardening (test set)

**Quality Score**: 90/100 (A - Excellent)
**Review Date**: 2026-08-08
**Review Scope**: directory (story test set — 3 files)
**Reviewer**: BMad TEA Agent

Note: This review audits existing tests; it does not generate tests. Coverage mapping and coverage gates are out of scope here. Use `trace` for coverage decisions.

## Executive Summary

**Overall Assessment**: Excellent

**Recommendation**: Block

<!-- COMPUTED, never chosen. steps-c/step-03f-aggregate-scores.md §3b derives this from the
     deduped violation counts: any CRITICAL => Block; any HIGH => Request Changes; score < 70 =>
     Request Changes; any remaining finding => Approve with Comments; otherwise Approve. -->

**Context Basis**: pr_diff

**Context Waivers Applied**: 0

<!-- Judged against the dw-web-e2e-config-hardening spec plus the changed source/config it
     exercises (tests/support/config.ts, playwright.config.ts, playwright.api.config.ts,
     tests/support/check-api-collection.mjs). Context can add findings and clarify impact; it
     cannot waive a violation, change severity, or alter the score. -->

### Key Strengths

✅ Process-global env mutation (the bundle's central concern) is reset defensively: `beforeEach`/`afterEach` delete, `afterAll` restores originals, and the Playwright scaffold pins `test.describe.configure({ mode: "serial" })` — H4 does not fire in any file.
✅ Every disabled test carries a documented, still-true reason (ATDD RED activation/regression locks mandated by the spec); the `test.skip` calls are exempt from C1 rather than silently dropped.
✅ All 25 test names carry priority markers (`[P0]`/`[P1]`/`[P2]`/`[P3]` or `WH-AC# [P#]`), and the active Vitest suite locks new behavior (query/fragment rejection, lazy legacy-`SYNCRO_API_BASE_URL` hint) with assertions visible in test bodies.
✅ No hard waits, wall-clock fixtures, conditional assertions, or unawaited async anywhere in the reviewed set.

### Key Weaknesses

❌ `config-hardening.atdd-red.spec.ts:45` contains `expect(true).toBe(true)` — the criteria registry's canonical tautological assertion (C3, CRITICAL). The prior review pass converted an un-assertable check into a placeholder that can never fail.
❌ The e2e scaffold assertions that remain are navigation/readiness checks only (`toHaveURL`, `body` visible); the server-boot evidence the scaffolds target lives in config-level tests, so the e2e layer holds little independent signal while it stays skipped.
❌ Env-value literals are duplicated between the fixture line and the assertion regex in several Vitest cases (e.g. `config.test.ts:101-106`), which couples the assertion to a byte-for-byte copy of the input.

### Summary

The reviewed set is small, focused, and unusually well-isolated for tests that mutate `process.env`: the unit suite (`config.test.ts`, 14 cases) locks the contract with explicit assertions, and both ATDD scaffolds restore env state and document their skip reasons. Quality is high at 90/100. The single CRITICAL finding is a one-line tautology introduced in the last review pass; the deterministic ledger maps it to `Block`, which is why the verdict is harsher than the rest of the suite would suggest.

---

## Quality Criteria Assessment

| Criterion                            | Status           | Violations | Basis                                    | Notes                                                                                                              |
| ------------------------------------ | ---------------- | ---------- | ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| BDD Format (Given-When-Then)         | ✅ PASS          | 0          | Convention: `bddNaming` (5 of 5)         | Names state behavior (returns/rejects/fails-fast/boots/serves), not bare method names.                             |
| Test IDs                             | ✅ PASS (n/a)    | 0          | Convention: `testIds` (0 of 5)           | Repo uses no test-id convention; only the e2e scaffold locates elements and it uses tag/role-level checks.          |
| Priority Markers (P0/P1/P2/P3)       | ✅ PASS          | 0          | Convention: `priorityMarkers` (4 of 5)   | Every reviewed test carries `[P#]` (or `WH-* [P#]`) in its name.                                                   |
| Disabled or Focused Tests            | ✅ PASS          | 0          | Absolute                                 | All `test.skip` calls carry documented still-true reasons (file header blocks); no `.only`/`fit`.                   |
| Hard Waits (sleep, waitForTimeout)   | ✅ PASS          | 0          | Absolute                                 | No bare timers anywhere in the review set.                                                                          |
| Determinism (no conditionals)        | ✅ PASS          | 0          | Absolute + Applicability                 | No conditional assertions, no wall-clock fixtures (no expiry/TTL/token boundaries asserted).                        |
| Isolation (cleanup, no shared state) | ✅ PASS          | 0          | Absolute                                 | Env restored in afterEach/afterAll; scaffold configured `mode: "serial"`; no unreset shared state (H4 n/a).         |
| Fixture Patterns                     | ✅ PASS (n/a)    | 0          | Applicability                            | No domain payloads constructed; env setup is minimal and inline (fixture extraction would add no value).            |
| Data Factories                       | ✅ PASS (n/a)    | 0          | Applicability                            | No domain payload shapes built; URL/env values are the input under test, not fixtures to factor.                    |
| Network-First Pattern                | ✅ PASS (n/a)    | 0          | Applicability                            | Scaffolds assert navigation/readiness only (URL, body); no data-dependent content read after navigation.           |
| Explicit Assertions                  | ❌ FAIL          | 1          | Absolute                                 | C3: `expect(true).toBe(true)` at `config-hardening.atdd-red.spec.ts:45`.                                            |
| Test Length (≤300 lines)             | ✅ PASS          | 140/123/55 | Absolute                                 | All files far under the 300-line threshold.                                                                         |
| Test Duration (≤1.5 min)             | ✅ PASS          | —          | Absolute                                 | No loops, sleeps, or repeated navigation found in static read; no measured runtime asserted.                        |
| Flakiness Patterns                   | ✅ PASS          | 0          | Absolute + Applicability                 | No H1/H2/H3/H4/M1/M6 evidence; only C3 (which is a coverage-evidence issue, not a flake source).                    |

**Total Violations**: 1 Critical, 0 High, 0 Medium, 0 Low

**Convention Baseline**: 5 test files sampled outside the review set (corpus is 5; all 5 sampled)

---

## Quality Score Breakdown

```
Starting Score:          100
Critical Violations:     -1 × 10 = -10
High Violations:         -0 × 5 = -0
Medium Violations:       -0 × 2 = -0
Low Violations:          -0 × 1 = -0

Bonus Points:
  Excellent BDD:         +0
  Comprehensive Fixtures: +0
  Data Factories:        +0
  Network-First:         +0
  Perfect Isolation:     +0
  All Test IDs:          +0
                         --------
Total Bonus:             +0

Final Score:             90/100
Grade:                   A
```

---

## Critical Issues (Must Fix)

### 1. Tautological assertion `expect(true).toBe(true)` in WH-P2-02

**Severity**: P0 (Critical)
**Location**: `syncro/apps/web/tests/e2e/config-hardening.atdd-red.spec.ts:45`
**Row**: C3
**Criterion**: Tautological assertion
**Knowledge Base**: [test-quality.md](../../.claude/skills/bmad-testarch-test-review/resources/knowledge/test-quality.md)

**Issue Description**:
The prior review pass (spec log: "WH-P2-02 scaffold honesty") rewrote the NODE_OPTIONS memory-guard check from an assertion that could never catch a dropped guard into `expect(true).toBe(true)`. That expression is the criteria registry's literal C3 example — an assertion that cannot fail. When this scaffold is activated it will report green while proving nothing, which is exactly the "suite reports green, evidence is zero" failure C3 exists to block. The registry row is CRITICAL, Absolute, and no context (including the spec's documented rationale) can waive it.

**Current Code**:

```typescript
test.skip("WH-P2-02 [P2] the web server runs with the NODE_OPTIONS memory guard applied", async () => {
    expect(true).toBe(true);
});
```

**Recommended Fix**:
Do not ship a body whose only statement can never fail. The check is genuinely un-assertable from the test process (child-process env is not reachable), so the honest forms are: (a) `test.fixme("...", async () => {})` — Playwright reports it as skipped-not-ok and it fails loudly the moment someone removes the marker without writing a real body; or (b) keep a `test.skip` with an empty/documented body and move the manual-evidence checklist to prose in the spec/README, never into an assertion.

```typescript
test.fixme(
  "WH-P2-02 [P2] the web server runs with the NODE_OPTIONS memory guard applied",
  async () => {
    // Manual evidence checklist: boot the suite on constrained CI and confirm the
    // dev server starts without an OOM/cryptic process kill. Child-process env is
    // not assertable from the test process; the config wiring itself is locked by
    // the config-contract unit suite. Re-enable only when a runtime probe exists.
  },
);
```

**Why This Matters**:
A test that cannot fail provides no evidence while reporting success — the registry treats this as worse than no test. It also trains reviewers to accept no-op assertions as a legitimate resolution pattern, which is how the next un-assertable check gets normalized.

**Related Violations**: None elsewhere in the review set.

---

## Recommendations (Should Fix)

No additional recommendations beyond the critical finding. The suite is otherwise clean.

---

## Best Practices Found

### 1. Defensive process.env isolation

**Location**: `syncro/apps/web/tests/support/config.test.ts:7-33`, `syncro/apps/web/tests/api/config-contract.atdd-red.spec.ts:24-40`
**Pattern**: Snapshot-restore of process-global state + serial mode
**Knowledge Base**: [test-quality.md](../../.claude/skills/bmad-testarch-test-review/resources/knowledge/test-quality.md)

**Why This Is Good**:
The tests mutate `process.env` (a process-global), the single most common isolation hazard in config tests. Each suite snapshots `BASE_URL`/`API_URL`/`SYNCRO_API_BASE_URL` at load, resets in `beforeEach`/`afterEach`, restores in `afterAll`, and the Playwright scaffold additionally pins `mode: "serial"` so mutations can never race another worker. This is the textbook H4 fix and it is why the isolation dimension scores 100.

**Code Example**:

```typescript
const ORIGINAL_ENV = {
  BASE_URL: process.env.BASE_URL,
  API_URL: process.env.API_URL,
  SYNCRO_API_BASE_URL: process.env.SYNCRO_API_BASE_URL,
};
// ...
afterAll(() => {
  if (ORIGINAL_ENV.SYNCRO_API_BASE_URL === undefined) delete process.env.SYNCRO_API_BASE_URL;
  else process.env.SYNCRO_API_BASE_URL = ORIGINAL_ENV.SYNCRO_API_BASE_URL;
});
```

**Use as Reference**:
Any future env-driven Playwright/Vitest suite should copy this snapshot-restore shape verbatim.

### 2. Documented, still-true skip reasons

**Location**: `config-contract.atdd-red.spec.ts:5-20`, `config-hardening.atdd-red.spec.ts:3-16`
**Pattern**: RED-phase activation/regression locks
**Knowledge Base**: [test-quality.md](../../.claude/skills/bmad-testarch-test-review/resources/knowledge/test-quality.md)

**Why This Is Good**:
Every `test.skip` is justified by a file-header block explaining it is an ACTIVATION/REGRESSION lock that must stay skipped until activated, with what passing/failing each case proves. The skip reasons are current and accurate (the spec mandates `test.skip` remain). This is exactly the documented-reason carve-out C1 demands, so no disabled-test finding fires.

**Use as Reference**:
When a scaffold must remain inert, document *why* and *what re-enables it* at the top of the file rather than leaving bare `.skip` calls.

---

## Test File Analysis

| File | Lines | Framework | Describe | Tests | Fixtures | Assertions |
| --- | --- | --- | --- | --- | --- | --- |
| `syncro/apps/web/tests/support/config.test.ts` | 140 | Vitest | 1 | 14 (active) | none (inline env setup) | `toBe`, `toThrow` |
| `syncro/apps/web/tests/api/config-contract.atdd-red.spec.ts` | 123 | Playwright | 1 | 12 (all `test.skip`) | `request` | `toBe`, `toThrow` |
| `syncro/apps/web/tests/e2e/config-hardening.atdd-red.spec.ts` | 55 | Playwright | 1 | 4 (all `test.skip`) | `page` | `toHaveURL`, `toBeVisible`, `toBe` |

---

## Context and Integration

### What the Context Said

The spec (`spec-web-e2e-config-hardening.md`) establishes the env-driven contract: `BASE_URL`/`API_URL` via lazy `webBaseUrl()`/`apiBaseUrl()` with fast descriptive errors, `/api/v1` suffix enforcement, trailing-slash normalization, query/fragment rejection, and the legacy `SYNCRO_API_BASE_URL` hint; `playwright.config.ts` derives `baseURL` and the web server command from `BASE_URL`; atdd specs stay `test.skip`. The active Vitest suite exercises exactly the newly patched behaviors (query/fragment rejection at `config.test.ts:101-116`, legacy hint at `:118-123`) and the env-restore was extended to `SYNCRO_API_BASE_URL` consistently in both scaffolds — the context confirms these tests map to the acceptance criteria. One context-shaped tension surfaced: the spec's own review log records that WH-P2-02 was deliberately rewritten to `expect(true).toBe(true)` "for honesty", which is precisely the practice the registry classifies as C3 CRITICAL. Context explains why the line exists; it cannot waive the row.

### Related Artifacts

- **Story File**: [_bmad-output/implementation-artifacts/spec-web-e2e-config-hardening.md](../implementation-artifacts/spec-web-e2e-config-hardening.md)
- **Test Design**: [_bmad-output/test-artifacts/test-design-story-web-e2e-config-hardening.md](test-design-story-web-e2e-config-hardening.md)

---

## Knowledge Base References

This review consulted the following knowledge base fragments:

- **[test-quality.md](../../.claude/skills/bmad-testarch-test-review/resources/knowledge/test-quality.md)** - Definition of Done for tests (no hard waits, <300 lines, <1.5 min, self-cleaning, explicit assertions)
- **[playwright-config.md](../../.claude/skills/bmad-testarch-test-review/resources/knowledge/playwright-config.md)** - Env-driven config with fail-fast validation, artifact output isolation, parallelization guardrails

For coverage mapping, consult `trace` workflow outputs.

See [tea-index.csv](../../.claude/skills/bmad-testarch-test-review/resources/tea-index.csv) for complete knowledge base.

---

## Next Steps

### Immediate Actions (Before Merge)

1. **Replace the tautological placeholder** - `config-hardening.atdd-red.spec.ts:45`: convert WH-P2-02 to `test.fixme(...)` (or drop the assertion body) so nothing can report green while proving nothing.
   - Priority: P0
   - Owner: web QA / dev
   - Estimated Effort: ~5 minutes

### Follow-up Actions (Future PRs)

1. **De-duplicate assertion literals** - `config.test.ts:101-106`: bind the input URL once (e.g. a local `const`) and reuse it in both `process.env` assignment and the `toThrow` regex so the two copies cannot drift.
   - Priority: P3
   - Target: next test-quality pass

### Re-Review Needed?

⚠️ Re-review after critical fixes - the C3 fix is one line; re-review, then the verdict moves to Approve.

---

## Decision

**Recommendation**: Block

**Rationale**:
The suite scores 90/100 (A) and every dimension is clean except determinism (80), which is dragged down solely by the CRITICAL tautology at `config-hardening.atdd-red.spec.ts:45`. The deduction ledger is deterministic: any CRITICAL yields `Block`. The finding is narrow and cheap to fix — replacing `expect(true).toBe(true)` with a `test.fixme` scaffold (or removing the no-op body) removes the CRITICAL, which moves the recommendation to `Approve` at the same score. This is a quality-bar verdict, not a signal that the config contract tests are weak; the active Vitest suite is exemplary.

**For Block**:

> Test quality is excellent at 90/100 but the criteria registry is absolute on tautological assertions: a test that can never fail provides no evidence while reporting green. One CRITICAL violation exists at `syncro/apps/web/tests/e2e/config-hardening.atdd-red.spec.ts:45`. Apply the one-line fix (see Critical Issue 1) and re-review; the verdict then becomes Approve.

---

## Appendix

### Violation Summary by Location

| Line | Severity | Criterion | Issue | Fix |
| --- | --- | --- | --- | --- |
| `config-hardening.atdd-red.spec.ts:45` | P0 | Tautological assertion | `expect(true).toBe(true)` can never fail | `test.fixme(...)` with a documented manual-evidence body |

### Quality Trends

| Review Date  | Score         | Grade     | Critical Issues | Trend       |
| ---          | ---           | ---       | ---             | ---         |
| 2026-08-08   | 90/100        | A         | 1               | — (first review of this set) |

---

## Review Metadata

**Generated By**: BMad TEA Agent (Test Architect)
**Workflow**: testarch-test-review v5.0
**Review ID**: test-review-dw-web-e2e-config-hardening-20260808
**Timestamp**: 2026-08-08
**Version**: 1.0

---

## Reviewed Files

- syncro/apps/web/tests/support/config.test.ts
- syncro/apps/web/tests/api/config-contract.atdd-red.spec.ts
- syncro/apps/web/tests/e2e/config-hardening.atdd-red.spec.ts

## Review Context

- _bmad-output/implementation-artifacts/spec-web-e2e-config-hardening.md
- syncro/apps/web/tests/support/config.ts
- syncro/apps/web/playwright.config.ts
- syncro/apps/web/playwright.api.config.ts
- syncro/apps/web/tests/support/check-api-collection.mjs

## Excluded From Review Set

- syncro/apps/web/tests/support/check-api-collection.mjs — format not scorable by the ledger
- syncro/apps/web/playwright.config.ts — format not scorable by the ledger
- syncro/apps/web/playwright.api.config.ts — format not scorable by the ledger
