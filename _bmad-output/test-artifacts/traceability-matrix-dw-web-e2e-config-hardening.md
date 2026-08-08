---
stepsCompleted: ['step-01-load-context', 'step-02-discover-tests', 'step-03-map-criteria', 'step-04-analyze-gaps', 'step-05-gate-decision']
lastStep: 'step-05-gate-decision'
lastSaved: '2026-08-08'
workflowType: 'testarch-trace'
inputDocuments:
  - '_bmad-output/implementation-artifacts/spec-web-e2e-config-hardening.md'
  - '_bmad-output/test-artifacts/test-design-story-web-e2e-config-hardening.md'
  - '_bmad-output/test-artifacts/atdd-checklist-dw-web-e2e-config-hardening.md'
  - '_bmad-output/test-artifacts/automation-summary-dw-web-e2e-config-hardening.md'
coverageBasis: 'acceptance_criteria'
oracleConfidence: 'high'
oracleResolutionMode: 'formal_requirements'
oracleSources:
  - '_bmad-output/implementation-artifacts/spec-web-e2e-config-hardening.md'
externalPointerStatus: 'not_used'
tempCoverageMatrixPath: '_bmad-output/test-artifacts/tea-trace-coverage-matrix-2026-08-08-story-dw-web-e2e-config-hardening.json'
---

# Traceability Matrix & Gate Decision - dw-web-e2e-config-hardening (env-driven Playwright config, DW-3 + DW-7)

**Target:** Story `dw-web-e2e-config-hardening`
**Date:** 2026-08-08
**Evaluator:** Yusuf (TEA Agent)
**Coverage Oracle:** acceptance_criteria (formal requirements — spec Tasks & Acceptance)
**Oracle Confidence:** high
**Oracle Sources:**
- `_bmad-output/implementation-artifacts/spec-web-e2e-config-hardening.md`
- `_bmad-output/test-artifacts/test-design-story-web-e2e-config-hardening.md`
- `_bmad-output/test-artifacts/atdd-checklist-dw-web-e2e-config-hardening.md`
- `_bmad-output/test-artifacts/automation-summary-dw-web-e2e-config-hardening.md`

---

Note: This workflow does not generate tests. If gaps exist, run `*atdd` or `*automate` to create coverage.

## PHASE 1: REQUIREMENTS TRACEABILITY

### Coverage Summary

| Priority | Total Criteria | FULL Coverage | Coverage % | Status      |
| -------- | -------------- | ------------- | ---------- | ----------- |
| P0       | 4              | 3             | 75%        | ❌ FAIL     |
| P1       | 1              | 0             | 0%         | ❌ FAIL     |
| P2       | 0              | 0             | 100%       | n/a         |
| P3       | 0              | 0             | 100%       | n/a         |
| **Total**| **5**          | **3**         | **60%**    | **❌ FAIL** |

**Legend:**

- ✅ PASS - Coverage meets quality gate threshold
- ⚠️ WARN - Coverage below threshold but not critical
- ❌ FAIL - Coverage below minimum threshold (blocker)

---

### Detailed Mapping

#### AC-1: Given `BASE_URL` set, when the Playwright config loads, then `use.baseURL` and `webServer.url` equal `BASE_URL` and the default `webServer.command` is the documented dev script (`npm run dev -- -p <port from BASE_URL>`), with no hardcoded port or `next/dist/bin/next` path in `playwright.config.ts`. (P0)

- **Coverage:** PARTIAL ⚠️
- **Tests:**
  - `CFG-UNIT-001` - syncro/apps/web/tests/support/config.test.ts:35
    - **Given:** `BASE_URL=http://localhost:3001/` set
    - **When:** `webBaseUrl()` is called
    - **Then:** returns `http://localhost:3001` (trailing slash stripped)
  - `CFG-UNIT-013` - syncro/apps/web/tests/support/config.test.ts:125
    - **Given:** portless `BASE_URL=http://app.example.com`
    - **When:** `webBaseUrl()` is called
    - **Then:** resolves without error
  - `CFG-UNIT-014` - syncro/apps/web/tests/support/config.test.ts:133
    - **Given:** default-port `BASE_URL=http://app.example.com:80`
    - **When:** `webBaseUrl()` is called
    - **Then:** resolves without error
  - `WH-AC1-E2E` - syncro/apps/web/tests/e2e/config-hardening.atdd-red.spec.ts:19 (SKIPPED - RED activation lock)
    - **Given:** the env-driven config and a real browser session
    - **When:** `/` is navigated
    - **Then:** the app is served at the baseURL derived from `BASE_URL` (redirect or login, body visible)
  - `WH-P1-04-E2E` - syncro/apps/web/tests/e2e/config-hardening.atdd-red.spec.ts:28 (SKIPPED - RED activation lock)
    - **Given:** `BASE_URL` carries a port
    - **When:** the web server boots via the derived command
    - **Then:** the server boots on the port parsed from `BASE_URL` (no 120s readiness timeout)
  - *Supporting static evidence (recorded in spec Verification):* grep guard confirms no `http://localhost:3001`, `next/dist/bin/next`, or port literal remains in `playwright.config.ts`; `playwright test --list` with `BASE_URL` set loads the config and derives `webServer` without error.
- **Gaps:**
  - Missing: Active automated assertion of `playwright.config.ts` `webServer.command` derivation (`npm run dev -- -p <port>`) and `webServer.url` wiring — currently locked only by the skipped RED-phase E2E scaffolds and static/manual probes.
- **Recommendation:** Activate `WH-AC1`/`WH-P1-04` E2E wiring scaffolds (remove `test.skip` with a live dev server), or add a config-level unit/assertion probe on `playwright.config.ts` so the command derivation is covered by an active test.

---

#### AC-2: Given `BASE_URL` unset, when the Playwright config loads, then it fails fast with a descriptive error naming `BASE_URL` and pointing at `syncro/apps/web/.env.example`. (P0)

- **Coverage:** FULL ✅
- **Tests:**
  - `CFG-UNIT-002` - syncro/apps/web/tests/support/config.test.ts:43
    - **Given:** `BASE_URL` unset
    - **When:** `webBaseUrl()` is called
    - **Then:** throws `/BASE_URL is required by Playwright tests/` including the `.env.example` pointer
  - `CFG-UNIT-003` - syncro/apps/web/tests/support/config.test.ts:48
    - **Given:** malformed `BASE_URL="not a url"`
    - **When:** `webBaseUrl()` is called
    - **Then:** throws the descriptive contract error naming `BASE_URL`
  - `CFG-UNIT-004` - syncro/apps/web/tests/support/config.test.ts:55
    - **Given:** whitespace-only `BASE_URL`
    - **When:** `webBaseUrl()` is called
    - **Then:** treated as unset; throws `BASE_URL is required`
  - `WH-AC2-RED` - syncro/apps/web/tests/api/config-contract.atdd-red.spec.ts:50 (SKIPPED - RED activation lock; duplicates the active unit coverage)
- **Gaps:** None (active unit coverage plus the recorded `playwright test --list` fail-fast probe).

---

#### AC-3: Given `API_URL` set, when `SyncroApiClient`, `audit-log.spec.ts`, `spareparts-atdd.spec.ts`, or `machine-sparepart-installations-atdd.spec.ts` resolves the backend base, then the base is `API_URL` (the `/api/v1`-suffixed URL) and no `http://localhost:8080` remains in test source. (P0)

- **Coverage:** FULL ✅
- **Tests:**
  - `CFG-UNIT-005` - syncro/apps/web/tests/support/config.test.ts:61
    - **Given:** `API_URL=http://localhost:8080/api/v1`
    - **When:** `apiBaseUrl()` is called
    - **Then:** returns the `/api/v1`-suffixed base as-is
  - `CFG-UNIT-006` - syncro/apps/web/tests/support/config.test.ts:69
    - **Given:** `API_URL` missing the `/api/v1` suffix
    - **When:** `apiBaseUrl()` is called
    - **Then:** throws `API_URL must include the /api/v1 base path`
  - `CFG-UNIT-007` - syncro/apps/web/tests/support/config.test.ts:76
    - **Given:** `API_URL=http://localhost:8080/api/v1/`
    - **When:** `apiBaseUrl()` is called
    - **Then:** returns `http://localhost:8080/api/v1` (no double-slash helpers)
  - `API-AUDIT-LOG` - syncro/apps/web/tests/api/audit-log.spec.ts:28 (api, token-gated live contract suite; consumes `apiBaseUrl()`)
  - `API-SPAREPARTS` - syncro/apps/web/tests/api/spareparts-atdd.spec.ts:38 (api, SKIPPED RED scaffold; consumes `apiBaseUrl()`)
  - `API-INSTALLATIONS` - syncro/apps/web/tests/api/machine-sparepart-installations-atdd.spec.ts:54 (api, SKIPPED RED scaffold; consumes `apiBaseUrl()`)
  - `CHECK-API-COLLECTION` - syncro/apps/web/tests/support/check-api-collection.mjs:73 (CI enumeration gate, active; lists 50 tests in 4 `tests/api` specs)
- **Gaps:** None for this criterion. The RED-phase API scaffolds are skipped by design (pre-existing); the shared base is actively unit-locked and the collection gate proves the consumers are enumerable. Static grep guard (recorded) confirms no `http://localhost:8080` in test source.

---

#### AC-4: Given `API_URL` unset, when a skipped atdd spec is imported, then the module loads and its tests skip cleanly; when a live api test actually runs, then `apiBaseUrl()` throws a descriptive error naming `API_URL`. (P0)

- **Coverage:** FULL ✅
- **Tests:**
  - `CFG-UNIT-008` - syncro/apps/web/tests/support/config.test.ts:84
    - **Given:** `API_URL` and `BASE_URL` unset
    - **When:** the config module is imported
    - **Then:** import succeeds (lazy); `apiBaseUrl()` throws `/API_URL is required/` only at the call site
  - `CFG-UNIT-009` - syncro/apps/web/tests/support/config.test.ts:95
    - **Given:** whitespace-only `API_URL`
    - **When:** `apiBaseUrl()` is called
    - **Then:** treated as unset; throws `API_URL is required`
  - `WH-AC4-RED` - syncro/apps/web/tests/api/config-contract.atdd-red.spec.ts:93 (SKIPPED - RED activation lock; duplicates the active unit coverage)
  - *Supporting:* `audit-log.spec.ts:30` token-gated `beforeEach` skip keeps the live suite CI-safe when no backend/tokens are configured.
- **Gaps:** None (lazy-import + call-site-throw contract actively locked; recorded `--list` probe with `API_URL` unset verifies clean skip).

---

#### AC-5: Given the suite, when `biome check`, `tsc --noEmit`, and `playwright test --list` (with env set) run, then all pass and the full spec set is enumerated. (P1)

- **Coverage:** PARTIAL ⚠️
- **Tests:**
  - `CFG-UNIT-SUITE` - syncro/apps/web/tests/support/config.test.ts:13 (unit, active; 14/14 vitest PASS recorded in the automate run — the config-contract surface behind the suite)
  - `CHECK-API-COLLECTION-2` - syncro/apps/web/tests/support/check-api-collection.mjs:73 (active; `test:api:check` exit 0, 50 tests in 4 files enumerated)
  - *Supporting recorded evidence (automation-summary-dw-web-e2e-config-hardening.md):* `npx tsc --noEmit` PASS; `npx biome check` clean; `playwright test --list` probes PASS (config loads under env set; descriptive error under unset/malformed).
- **Gaps:**
  - Missing: `biome check` / `tsc --noEmit` were verified in the parent repo via an isolated temp harness with installed deps, not reproducible in this dependency-less worktree — re-run in the parent repo before merge.
  - Missing: "full spec set enumerated" holds only via the dedicated `test:api` config (`playwright.api.config.ts`, testDir `./tests/api`); the default `testDir: ./tests/e2e` still omits the API suites (DW-10, mitigated by `test:api` + `test:api:check` in CI).
- **Recommendation:** Wire `npm run test:api` + `npm run test:api:check` into CI (with `BASE_URL`/`API_URL` exported) and re-run `biome check` + `tsc --noEmit` in the parent repo; treat the recorded verification as the AC-5 evidence.

---

### Gap Analysis

#### Critical Gaps (BLOCKER) ❌

0 gaps found.

---

#### High Priority Gaps (PR BLOCKER) ⚠️

0 fully-uncovered gaps found. (AC-1 is P0 PARTIAL — see below.)

---

#### Medium Priority Gaps (Nightly) ⚠️

2 gaps found.

1. **AC-1: playwright.config.ts webServer wiring / command derivation** (P0, PARTIAL)
   - Current Coverage: PARTIAL
   - Missing Tests: active automated assertion of the derived `npm run dev -- -p <port>` command and `webServer.url` wiring (currently skipped RED-phase E2E scaffolds + static/manual probes).
   - Recommend: activate `WH-AC1`/`WH-P1-04` (`config-hardening.atdd-red.spec.ts`) or add a config-level probe.
   - Impact: no hard-failing regression lock for the bundle's headline config change; a future hardcoded-port or broken-port-derivation regression would slip through CI green.

2. **AC-5: toolchain verification + full enumeration** (P1, PARTIAL)
   - Current Coverage: PARTIAL
   - Missing Tests: reproducible `biome check`/`tsc --noEmit` in a deps-installed environment; default-`testDir` API enumeration (DW-10) gated only by `test:api` config + check script.
   - Recommend: parent-repo verification + CI wiring of `test:api`/`test:api:check`.
   - Impact: toolchain regressions or a green-but-empty API run are not surfaced by the default pipeline.

---

#### Low Priority Gaps (Optional) ℹ️

0 gaps found.

---

### Coverage Heuristics Findings

#### Endpoint Coverage Gaps

- Endpoints without direct API tests: 0
- Examples: (none — config chore has no new endpoints)

#### Auth/Authz Negative-Path Gaps

- Criteria missing denied/invalid-path tests: 0
- Examples: (none — no auth/authz surface in this bundle)

#### Happy-Path-Only Criteria

- Criteria missing error/edge scenarios: 0 (AC-2/AC-4 error paths actively locked by the unit suite)

#### UI Journey / E2E Wiring Coverage

- Journeys with no ACTIVE E2E coverage: 1 (AC-1 — webServer/baseURL wiring locked by skipped RED scaffolds)
- UI state coverage: present (config-load error/edge states unit-locked)

---

### Quality Assessment

#### Tests with Issues

**WARNING Issues** ⚠️

- `WH-AC1-E2E` / `WH-P1-04-E2E` (`config-hardening.atdd-red.spec.ts`) - skipped by design (RED-phase activation locks needing a live dev server/browser); leaving them skipped keeps AC-1 at PARTIAL - activate or explicitly accept as evidence locks.
- `API-SPAREPARTS` / `API-INSTALLATIONS` - skipped RED-phase scaffolds, pre-existing; they are the primary consumers of the bundle's `apiBaseUrl()` but do not run by default - run via `npm run test:api`.

#### Tests Passing Quality Gates

**16/20 tests (80%) are active; the 4 skipped entries are documented RED-phase/activation scaffolds.** ✅

---

### Duplicate Coverage Analysis

#### Acceptable Overlap (Defense in Depth)

- AC-2/AC-4: `config.test.ts` active unit locks + the atdd-red scaffold duplicates (WH-AC2/WH-AC4) as activation locks ✅

#### Unacceptable Duplication ⚠️

None.

---

### Coverage by Test Level

| Test Level | Tests | Criteria Covered | Coverage % |
| ---------- | ----- | ---------------- | ---------- |
| Unit       | 14    | 5                | 100%       |
| API        | 4     | 3                | 60%        |
| Component  | 0     | 0                | n/a        |
| E2E        | 1     | 1                | 20%        |
| Other (check) | 1  | 2                | 40%        |
| **Total**  | **20**| **5**            |            |

---

### Traceability Recommendations

#### Immediate Actions (Before PR Merge)

1. **Activate or accept the E2E wiring scaffolds (AC-1)** - remove `test.skip(` from `WH-AC1`/`WH-P1-04` (`config-hardening.atdd-red.spec.ts`) and run against a live dev server, or explicitly record them as evidence locks and add a config-level unit assertion on `playwright.config.ts` command derivation. P0 coverage is currently 75% because of this.
2. **Wire `test:api` + `test:api:check` into CI** - with `BASE_URL`/`API_URL` exported, so the API suites (the primary `apiBaseUrl()` consumers) are collected and a green-but-empty API run surfaces (AC-5).
3. **Re-run `biome check` + `tsc --noEmit` in the parent repo** (deps installed) and record the result as AC-5 evidence.

#### Short-term Actions (This Milestone)

1. **Run `test:api` locally/CI** (`playwright.api.config.ts`, 50 tests in 4 files) after the parent-repo verification.

#### Long-term Actions (Backlog)

1. **Run `/bmad:tea:test-review`** on `config.test.ts` and `check-api-collection.mjs`.

---

## PHASE 2: QUALITY GATE DECISION

**Gate Type:** story
**Decision Mode:** deterministic

---

### Evidence Summary

#### Coverage Summary (from Phase 1)

**Requirements Coverage:**

- **P0 Acceptance Criteria**: 3/4 covered (75%) ❌
- **P1 Acceptance Criteria**: 0/1 covered (0%) ❌
- **Overall Coverage**: 60%

**Coverage Source**: `_bmad-output/test-artifacts/tea-trace-coverage-matrix-2026-08-08-story-dw-web-e2e-config-hardening.json`

---

### Decision Criteria Evaluation

#### P0 Criteria (Must ALL Pass)

| Criterion       | Threshold | Actual     | Status          |
| --------------- | --------- | ---------- | --------------- |
| P0 Coverage     | 100%      | 75%        | ❌ FAIL         |

**P0 Evaluation**: ❌ ONE OR MORE FAILED

#### P1 Criteria (Required for PASS, May Accept for CONCERNS)

| Criterion              | Threshold     | Actual | Status |
| ---------------------- | ------------- | ------ | ------ |
| P1 Coverage            | ≥90% (target) | 0%     | ❌ FAIL |
| Overall Coverage       | ≥80%          | 60%    | ❌ FAIL |

**P1 Evaluation**: ❌ FAILED

---

### GATE DECISION: FAIL

---

### Rationale

**P0 coverage is 75% (required: 100%).** AC-1 — the bundle's headline requirement that the Playwright config derive `baseURL`/`webServer.url` from `BASE_URL` and default `webServer.command` to `npm run dev -- -p <port>` with no hardcoded port or `next/dist/bin/next` path — is only PARTIALLY covered. The base-URL value and the no-hardcoded-literal clauses are actively locked (`config.test.ts` unit suite + recorded static grep guard), but the `webServer.command` derivation and `webServer.url` wiring are asserted only by the **skipped RED-phase E2E scaffolds** (`WH-AC1`/`WH-P1-04`) and manual/config-load probes, not by an active automated test.

**P1 coverage is 0%** for AC-5: `biome check`/`tsc --noEmit` passed in a parent-repo isolated harness but are not reproducible in this dependency-less worktree, and full spec enumeration depends on the `test:api` config (`playwright.api.config.ts`) because the default `testDir: ./tests/e2e` omits `tests/api` (DW-10).

**Overall coverage is 60%** (minimum: 80%).

The config-contract core is well covered (AC-2/AC-3/AC-4 FULL via an active 14-test unit suite), and all skipped tests are documented by-design RED-phase/activation scaffolds — not unexpected failures. The gate is nonetheless FAIL under the deterministic rule set because one P0 criterion lacks an active regression lock and the toolchain/enumeration evidence is not yet reproducible in the canonical environment. Release of this bundle should be gated on activating/accepting the E2E wiring scaffolds (or a config-level assertion) and closing the AC-5 toolchain/enumeration evidence in CI.

**Assumptions / caveats:** skipped tests are intentionally RED-phase activation locks from the TEA ATDD run for this bundle; the spec itself is marked `done` and has passed three adversarial review passes (25+ patches). The FAIL here reflects active-coverage rigor, not a functional defect in the working tree.

---

#### Critical Issues (For FAIL or CONCERNS)

| Priority | Issue | Description | Owner | Due Date | Status |
| -------- | ----- | ----------- | ----- | -------- | ------ |
| P0 | AC-1 wiring not actively locked | `playwright.config.ts` webServer command/url derivation covered only by skipped RED scaffolds | DEV team | pre-merge | OPEN |
| P1 | AC-5 evidence not reproducible | `biome`/`tsc` verified in isolated parent-repo harness; full enumeration via `test:api` only (DW-10) | DEV team | pre-merge | OPEN |

**Blocking Issues Count**: 1 P0 (coverage gap), 1 P1 (coverage gap)

---

#### For FAIL Decision ❌

1. **Block Deployment Immediately**
   - Do NOT merge as-is under the strict gate; address AC-1 active coverage and AC-5 evidence first.
2. **Fix Critical Issues**
   - Activate `WH-AC1`/`WH-P1-04` E2E wiring scaffolds (or add a config-level unit assertion on `playwright.config.ts`) to bring P0 to 100%.
   - Wire `test:api`/`test:api:check` into CI and re-run `biome check` + `tsc --noEmit` in the parent repo to close AC-5.
3. **Re-Run Gate After Fixes**
   - Re-run `bmad tea *trace` for `dw-web-e2e-config-hardening` and verify PASS/CONCERNS before deploying.

---

### Next Steps

**Immediate Actions** (next 24-48 hours):

1. Activate or accept the E2E wiring scaffolds for AC-1 (or add a config-level assertion).
2. Wire `test:api` + `test:api:check` into the web CI stage with `BASE_URL`/`API_URL` exported.
3. Re-run `biome check` + `tsc --noEmit` in the parent repo and record the results.

**Follow-up Actions** (next milestone/release):

1. Run TEA `test-review` on `config.test.ts` and `check-api-collection.mjs`.
2. Promote the skipped RED-phase API scaffolds (`tests/api/*`) via `npm run test:api` in CI.

**Stakeholder Communication:**

- Notify PM: gate FAIL on active-coverage rigor (AC-1 E2E wiring + AC-5 toolchain evidence), not on functional defects.
- Notify SM: 1 P0 + 1 P1 coverage item to close before merge.
- Notify DEV lead: activate E2E wiring scaffolds or add a config-level assertion; wire `test:api` into CI.

---

## Sign-Off

**Phase 1 - Traceability Assessment:**

- Overall Coverage: 60%
- P0 Coverage: 75% ❌
- P1 Coverage: 0% ❌
- Critical Gaps: 0
- High Priority Gaps: 0

**Phase 2 - Gate Decision:**

- **Decision**: FAIL ❌
- **P0 Evaluation**: ❌ ONE OR MORE FAILED
- **P1 Evaluation**: ❌ FAILED

**Overall Status:** FAIL ❌

**Next Steps:**

- If PASS ✅: Proceed to deployment
- If CONCERNS ⚠️: Deploy with monitoring, create remediation backlog
- If FAIL ❌: Block deployment, fix critical issues, re-run workflow
- If WAIVED 🔓: Deploy with business approval and aggressive monitoring

**Generated:** 2026-08-08
**Workflow:** testarch-trace v5.0 (Step-File Architecture, Enhanced with Gate Decision)

---

<!-- Powered by BMAD-CORE™ -->
