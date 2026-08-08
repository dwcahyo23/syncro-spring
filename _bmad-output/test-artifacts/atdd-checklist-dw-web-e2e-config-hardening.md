---
stepsCompleted: ['step-01-preflight-and-context', 'step-02-generation-mode', 'step-03-test-strategy', 'step-04c-aggregate', 'step-05-validate-and-complete']
lastStep: 'step-05-validate-and-complete'
lastSaved: '2026-08-08'
workflowType: 'testarch-atdd'
storyId: 'dw-web-e2e-config-hardening'
storyKey: 'dw-web-e2e-config-hardening'
storyFile: '_bmad-output/implementation-artifacts/spec-web-e2e-config-hardening.md'
atddChecklistPath: '_bmad-output/test-artifacts/atdd-checklist-dw-web-e2e-config-hardening.md'
generatedTestFiles:
  - 'syncro/apps/web/tests/api/config-contract.atdd-red.spec.ts'
  - 'syncro/apps/web/tests/e2e/config-hardening.atdd-red.spec.ts'
inputDocuments:
  - '_bmad-output/implementation-artifacts/spec-web-e2e-config-hardening.md'
  - '_bmad-output/implementation-artifacts/deferred-work.md'
  - '_bmad-output/test-artifacts/test-design-story-web-e2e-config-hardening.md'
  - '_bmad-output/test-artifacts/test-design-progress.md'
  - 'syncro/apps/web/tests/support/config.ts'
  - 'syncro/apps/web/playwright.config.ts'
  - 'syncro/apps/web/tests/support/helpers/syncro-api-client.ts'
  - 'syncro/apps/web/tests/api/audit-log.spec.ts'
  - 'syncro/apps/web/tests/api/spareparts-atdd.spec.ts'
  - 'syncro/apps/web/tests/api/machine-sparepart-installations-atdd.spec.ts'
  - 'syncro/apps/web/.env.example'
  - 'syncro/apps/web/tests/README.md'
---

# ATDD Checklist - Deferred-Work Bundle: Env-driven Playwright test config (DW-3 + DW-7)

**Date:** 2026-08-08
**Author:** Yusuf (TEA / bmad-testarch-atdd)
**Primary Test Level:** Config contract (unit/API probes) + E2E wiring. Test-infrastructure-only chore — no production behavior surface.

---

## Story Summary

The bundle externalizes and hardens the web Playwright test config: a single lazy env-driven config source (`tests/support/config.ts`) resolves `BASE_URL` (web) and `API_URL` (backend, includes `/api/v1`); `playwright.config.ts` derives `baseURL` and the default web server command from `BASE_URL` (`npm run dev -- -p <port>`) while keeping `PLAYWRIGHT_WEB_SERVER_COMMAND` as the override and restoring the `--max-old-space-size=2048` memory guard via `webServer.env.NODE_OPTIONS`; every API helper/spec consumes the shared `apiBaseUrl()`. Resolves DW-3 (hardcoded local ports) and DW-7 (brittle Next.js server command).

**As a** test-infrastructure engineer
**I want** the Playwright web config to be env-driven with descriptive fail-fast errors
**So that** the suite is portable across CI/local, never hardcodes ports or internal Next.js binary paths, and operators get actionable errors when env is missing.

> **Run context:** Story `dw-web-e2e-config-hardening` is already implemented and partially verified in the working tree (spec `status: done`; `playwright test --list` probes PASS). This ATDD run is a **gap-closing red-phase scaffold** produced from the story-level test design (`test-design-story-web-e2e-config-hardening.md`): it adds RED scaffolds that lock the config contract (`config.ts` behaviors) and the E2E wiring (baseURL/webServer), plus an implementation checklist that closes the flagged gaps (WH-01 API-suite collection / DW-10, DW-11 ledger entries, `config.ts` unit suite, `NODE_OPTIONS` merge decision).

---

## Acceptance Criteria

1. Given `BASE_URL` set, when the Playwright config loads, then `use.baseURL` and `webServer.url` equal `BASE_URL` and the default `webServer.command` is the documented dev script (`npm run dev -- -p <port from BASE_URL>`), with no hardcoded port or `next/dist/bin/next` path in `playwright.config.ts`.
2. Given `BASE_URL` unset, when the Playwright config loads, then it fails fast with a descriptive error naming `BASE_URL` and pointing at `syncro/apps/web/.env.example`.
3. Given `API_URL` set, when `SyncroApiClient`, `audit-log.spec.ts`, `spareparts-atdd.spec.ts`, or `machine-sparepart-installations-atdd.spec.ts` resolves the backend base, then the base is `API_URL` (the `/api/v1`-suffixed URL) and no `http://localhost:8080` remains in test source.
4. Given `API_URL` unset, when a skipped atdd spec is imported, then the module loads and its tests skip cleanly; when a live api test actually runs, then `apiBaseUrl()` throws a descriptive error naming `API_URL`.
5. Given the suite, when `biome check`, `tsc --noEmit`, and `playwright test --list` (with env set) run, then all pass and the full spec set is enumerated.

---

## Story Integration Metadata

- **Story ID:** `dw-web-e2e-config-hardening`
- **Story Key:** `dw-web-e2e-config-hardening`
- **Story File:** `_bmad-output/implementation-artifacts/spec-web-e2e-config-hardening.md`
- **Checklist Path:** `_bmad-output/test-artifacts/atdd-checklist-dw-web-e2e-config-hardening.md`
- **Generated Test Files:**
  - `syncro/apps/web/tests/api/config-contract.atdd-red.spec.ts`
  - `syncro/apps/web/tests/e2e/config-hardening.atdd-red.spec.ts`

---

## Red-Phase Test Scaffolds Created

### API / Config-Contract Tests (11 tests — all `test.skip()`)

**File:** `syncro/apps/web/tests/api/config-contract.atdd-red.spec.ts`

Tests the pure config contract of the new `tests/support/config.ts` module (lazy `webBaseUrl()`/`apiBaseUrl()`). Serial-describe because the tests mutate `process.env`. These are activation/regression locks: expected green today (implementation present), RED if the contract regresses.

- ✅ **Test:** `WH-AC1 [P0] webBaseUrl() returns the BASE_URL value with a trailing slash stripped`
  - **Status:** RED (activation lock) - fails if trailing-slash normalization is removed or a hardcoded fallback returns.
  - **Verifies:** AC1 (web base resolution) + trailing-slash normalization (spec review patch).
- ✅ **Test:** `WH-AC2 [P0] webBaseUrl() fails fast naming BASE_URL and the .env.example pointer when unset`
  - **Status:** RED (activation lock) - fails if the missing-var error loses the variable name or the `.env.example` pointer (module-resolved path).
  - **Verifies:** AC2.
- ✅ **Test:** `WH-AC2b [P0] webBaseUrl() rejects a malformed BASE_URL with the descriptive contract error`
  - **Status:** RED (activation lock) - fails if `requireUrl()` validation is dropped and `ERR_INVALID_URL` leaks.
  - **Verifies:** AC2 (present-but-invalid URL contract).
- ✅ **Test:** `WH-AC2c [P0] whitespace-only BASE_URL is treated as unset (requireEnv trims)`
  - **Status:** RED (activation lock) - fails if `requireEnv()` stops trimming before validating.
  - **Verifies:** AC2 (whitespace edge, spec review patch).
- ✅ **Test:** `WH-AC3 [P0] apiBaseUrl() returns the API_URL base including the /api/v1 base path`
  - **Status:** RED (activation lock) - fails if the `/api/v1`-suffixed base is not returned as-is.
  - **Verifies:** AC3.
- ✅ **Test:** `WH-AC3b [P0] apiBaseUrl() rejects API_URL missing the /api/v1 suffix with a descriptive error`
  - **Status:** RED (activation lock) - fails if the suffix-enforcement check is removed.
  - **Verifies:** AC3 (suffix contract, spec review patch).
- ✅ **Test:** `WH-AC3c [P0] apiBaseUrl() strips a trailing slash so helpers never produce double-slash URLs`
  - **Status:** RED (activation lock) - fails if trailing-slash stripping regresses (would produce `//audit-log`).
  - **Verifies:** AC3 (trailing-slash edge, spec review patch).
- ✅ **Test:** `WH-AC4 [P0] importing config with API_URL unset never throws; the descriptive error surfaces only at the call site`
  - **Status:** RED (activation lock) - fails if the module throws at import time instead of lazily.
  - **Verifies:** AC4 (lazy-resolution contract).
- ✅ **Test:** `WH-AC4b [P0] whitespace-only API_URL is treated as unset (requireEnv trims)`
  - **Status:** RED (activation lock) - fails if `requireEnv()` stops trimming before validating.
  - **Verifies:** AC4 (whitespace edge).
- ✅ **Test:** `WH-P2-01 [P2] a portless BASE_URL (deployed origin) resolves without error`
  - **Status:** RED (activation lock) - documents WH-03; the value itself must resolve (the readiness hang pairing is a config-level, documented limitation).
  - **Verifies:** portless `BASE_URL` contract (T-WH-P2-01).
- ✅ **Test:** `WH-P3-01 [P3] a default-port BASE_URL resolves without error (config-level port derivation is a separate probe)`
  - **Status:** RED (activation lock) - documents WH-09; config.ts must resolve, the port-derivation edge is probed separately.
  - **Verifies:** URL-parse edge (T-WH-P3-02 partial).

### E2E Wiring Tests (4 tests — all `test.skip()`)

**File:** `syncro/apps/web/tests/e2e/config-hardening.atdd-red.spec.ts`

Tests the wiring between the env-driven config and a real browser session (needs the playwright.config `webServer` to boot the app).

- ✅ **Test:** `WH-AC1 [P0] the app is served at the baseURL derived from BASE_URL`
  - **Status:** RED (activation lock) - fails if `baseURL` is not derived from `BASE_URL` (hardcoded localhost regression) and the app is unreachable at `/`.
  - **Verifies:** AC1 (E2E wiring, T-WH-P1-04).
- ✅ **Test:** `WH-P1-04 [P1] the web server boots on the port parsed from BASE_URL`
  - **Status:** RED (activation lock) - fails if port derivation is broken (readiness timeout or wrong-port serve).
  - **Verifies:** T-WH-P1-04 / WH-06, WH-08.
- ✅ **Test:** `WH-P2-02 [P2] the web server runs with the NODE_OPTIONS memory guard applied`
  - **Status:** RED (evidence lock) - child-process env cannot be asserted directly; the scaffold documents the evidence requirement (boot on constrained CI without OOM). Expected green once the guard survives.
  - **Verifies:** WH-06 (memory guard survival).
- ✅ **Test:** `WH-P2-04 [P2] PLAYWRIGHT_WEB_SERVER_COMMAND override wins over the derived default`
  - **Status:** RED (activation lock) - fails if the override is ignored or breaks readiness.
  - **Verifies:** WH-07 override matrix (T-WH-P2-04).

---

## Data Factories Created

None. Config-contract probes use inline env values; no entity data needed.

---

## Fixtures Created

None. E2E scaffolds reuse the existing project `support/fixtures` (`test`/`expect`). No new fixture infrastructure required for this test-infra chore.

---

## Mock Requirements

None. No external service mocking; E2E scaffolds require the real dev server (or `PLAYWRIGHT_SKIP_WEB_SERVER=1` against an already-running app).

---

## Required data-testid Attributes

Not applicable — no production UI surface in this story; assertions use URL/body-level checks only.

---

## Implementation Checklist

> Red-phase scaffolds stay `test.skip()` until a developer activates the current task. Activate ONE at a time: remove `test.skip(` from that test, run it, and confirm it either passes (regression lock) or fails for a real contract regression before fixing. These are gap-closing activation/evidence locks on an already-implemented bundle; the checklist also carries the flagged follow-ups from the test design.

### Test: WH-AC1..WH-AC4b (P0 config-contract, 9 tests)

**File:** `syncro/apps/web/tests/api/config-contract.atdd-red.spec.ts`

**Tasks to make these tests pass:**

- [ ] Run the suite: `npx playwright test tests/api/config-contract.atdd-red.spec.ts` (from `syncro/apps/web`; requires `node_modules` — not present in this worktree).
- [ ] Expected: all green (implementation present). Any red = real contract regression in `tests/support/config.ts` (lazy resolution, trim, URL validation, `/api/v1` suffix, trailing-slash normalization, error text contents).
- [ ] **Gap to close (WH-01 / DW-10):** this file lives under `tests/api/` which is OUTSIDE the default `testDir: ./tests/e2e`. Add a `test:api` npm script (`playwright test tests/api`) or a second project/testDir so the API suites — the bundle's primary `apiBaseUrl()` consumers — are collected and executed in CI. Without this, the suite can be green with zero API-suite execution.
- [ ] **Gap to close (test-design T-WH-P1-02):** the `config.ts` module still has zero unit tests outside this Playwright spec. Optionally add a vitest unit suite (`tests/support/config.spec.ts` + extend `vitest.config.ts` `include` or a dedicated project) for instant feedback; the Playwright spec is the CI-carrying contract lock.
- [ ] ✅ Tests pass (green phase)

**Estimated Effort:** 1-2 h (mostly the collection fix)

### Test: WH-AC1 / WH-P1-04 (P0/P1 E2E wiring, 2 tests)

**File:** `syncro/apps/web/tests/e2e/config-hardening.atdd-red.spec.ts`

**Tasks to make these tests pass:**

- [ ] Run: `npm --prefix syncro/apps/web exec playwright test tests/e2e/config-hardening.atdd-red.spec.ts` with `BASE_URL=http://localhost:3001` and `API_URL=http://localhost:8080/api/v1` set (playwright.config webServer boots the app).
- [ ] Confirm the dev server boots on the port parsed from `BASE_URL` (default command `npm run dev -- -p 3001`) and the app serves at `/`.
- [ ] Expected: green. Any red = baseURL/port derivation or readiness regression in `playwright.config.ts`.
- [ ] ✅ Tests pass (green phase)

**Estimated Effort:** 0.5-1 h

### Test: WH-P2-02 (P2 memory-guard evidence lock)

**File:** `syncro/apps/web/tests/e2e/config-hardening.atdd-red.spec.ts`

**Tasks to complete this item:**

- [ ] Run the E2E suite on constrained CI with the derived `npm run dev` command; confirm the dev server boots without OOM/cryptic process kill (guard survives via `webServer.env.NODE_OPTIONS`).
- [ ] **Decision to record (WH-04):** `webServer.env.NODE_OPTIONS` currently REPLACES operator/CI-provided `NODE_OPTIONS` (e.g. OpenSSL legacy-provider flags). Decide append-vs-replace (e.g. `${process.env.NODE_OPTIONS ?? ""} --max-old-space-size=2048`) and document in `tests/README.md` / `.env.example`.
- [ ] ✅ Evidence captured + decision documented (green phase)

**Estimated Effort:** 0.5-1 h

### Test: WH-P2-04 (P2 override matrix)

**File:** `syncro/apps/web/tests/e2e/config-hardening.atdd-red.spec.ts`

**Tasks to complete this item:**

- [ ] Run with `PLAYWRIGHT_WEB_SERVER_COMMAND` set to a custom command and confirm the server comes up and serves the baseURL.
- [ ] Run with `PLAYWRIGHT_WEB_SERVER_COMMAND=""` and whitespace value; confirm the trimmed override falls back to the derived default (T-WH-P2-04 matrix).
- [ ] ✅ Override matrix verified (green phase)

**Estimated Effort:** 0.5 h

### Test: WH-P2-01 / WH-P3-01 (P2/P3 portless + URL-parse edges)

**File:** `syncro/apps/web/tests/api/config-contract.atdd-red.spec.ts`

**Tasks to complete this item:**

- [ ] Confirm `webBaseUrl()` resolves portless and default-port URLs (already asserted in scaffolds).
- [ ] **Document known limitation (WH-03 / DW-11):** portless `BASE_URL` with webServer enabled still hangs on readiness (portless origin vs. Next default port); only `PLAYWRIGHT_SKIP_WEB_SERVER=1` pairing avoids it. Add a DW-11 ledger entry (see next item).
- [ ] Add explicit URL-parse probes for IPv6 `[::1]:3001` and default-port `:80`/`:443` derivation in `playwright.config.ts` (T-WH-P3-02) if evidence is cheap.
- [ ] ✅ Edges documented/probed (green phase)

**Estimated Effort:** 0.5-1 h

### Static guards (AC3/AC5 supporting evidence)

**File:** none (repo-wide static checks)

**Tasks to complete these items:**

- [ ] Run: `rg -n "http://localhost:8080|http://localhost:3001|next/dist/bin/next|SYNCRO_API_BASE_URL" syncro/apps/web/playwright.config.ts syncro/apps/web/tests` — expect NO matches (P0-05 gate; already verified in spec).
- [ ] Run: `npm --prefix syncro/apps/web run check` (Biome) and `npx --prefix syncro/apps/web tsc --noEmit` — NOT runnable in this dependency-less worktree; MUST be re-run in the parent repo before merge (AC5).
- [ ] **Ledger gap (flagged in test design):** DW-10/DW-11 are referenced as "deferred to ledger" in the spec review log but are NOT present in `_bmad-output/implementation-artifacts/deferred-work.md` (only DW-1..DW-9). Add the two entries (API specs outside default `testDir`; portless `BASE_URL` readiness hang).
- [ ] **CI coverage-gap signal (T-WH-P2-03):** add a check that asserts the API suites were collected (`playwright test --list | grep tests/api` non-empty) so a green-but-empty API run fails loudly.
- [ ] ✅ Guards pass / gaps closed

**Estimated Effort:** 1-2 h

---

## Running Tests

```bash
# From syncro/apps/web (node_modules required; not present in this worktree)
export BASE_URL=http://localhost:3001
export API_URL=http://localhost:8080/api/v1

# Config-contract RED scaffolds (after the tests/api collection fix — T-WH-P1-01)
npx playwright test tests/api/config-contract.atdd-red.spec.ts

# E2E wiring RED scaffolds (playwright.config webServer boots the app)
npx playwright test tests/e2e/config-hardening.atdd-red.spec.ts

# Existing P0 gate probes (recorded in spec Verification)
npm --prefix syncro/apps/web exec playwright test --list

# Static guards (AC3/AC5)
rg -n "http://localhost:8080|http://localhost:3001|next/dist/bin/next|SYNCRO_API_BASE_URL" syncro/apps/web/playwright.config.ts syncro/apps/web/tests

# Parent repo only (deps installed)
npm --prefix syncro/apps/web run check
npx --prefix syncro/apps/web tsc --noEmit
```

---

## Red-Green-Refactor Workflow

### RED Phase (Complete) ✅

- ✅ All tests written as red-phase scaffolds with `test.skip()`: 11 config-contract + 4 E2E wiring.
- ✅ Scaffolds assert EXPECTED behavior (contract values, error text, wiring outcomes) — no placeholder assertions.
- ✅ Fixture/factory/mock/data-testid sections marked N/A where not applicable; no production UI surface.
- ⚠️ Scaffolds NOT executed in this worktree (no `node_modules`); activation is green-phase work. Compile/lint not verifiable here.

### GREEN Phase (DEV Team - Next Steps)

1. Pick one scaffolded test from the implementation checklist (start with the P0 config-contract suite).
2. Remove `test.skip(` for that test and confirm it passes (regression lock) or fails for a real regression before fixing.
3. Close the flagged gaps: `tests/api/**` collection (WH-01/DW-10), `config.ts` unit suite (T-WH-P1-02), DW-10/DW-11 ledger entries, `NODE_OPTIONS` append-vs-replace decision (WH-04), CI coverage-gap signal (T-WH-P2-03).
4. Check off each task; move to the next.

### REFACTOR Phase (DEV Team - After All Tests Pass)

1. Verify all activated tests pass; review for quality.
2. Re-run `biome check` + `tsc --noEmit` in the parent repo (AC5).
3. Update `test-design-story-web-e2e-config-hardening.md` risk statuses (WH-01..WH-09).
4. When all activated tests pass, update story status in `sprint-status.yaml`.

---

## Notes

- **Activation-lock vs evidence-lock:** all config-contract + E2E wiring scaffolds are activation/regression locks (expected green today). `WH-P2-02` is an evidence lock (child-process env not directly assertable); the deliverable is the documented NODE_OPTIONS merge decision + constrained-CI boot evidence.
- **Collection gap (WH-01 / DW-10):** the flagship operational gap. The bundle's primary `apiBaseUrl()` consumers (`tests/api/*`) are NOT collected by the default `testDir: ./tests/e2e`; even the new config-contract spec is outside default collection. Closing this (a `test:api` script or second project) is the single highest-value action.
- **Env-required contract (WH-02):** intentional fail-fast breaking change; a CI pipeline not exporting `BASE_URL`/`API_URL` fails at config load. Preflight the CI env before rollout.
- **NODE_OPTIONS (WH-04):** current hardcode replaces operator flags for the server process — decide append-vs-replace.
- **Ledger gap:** DW-10/DW-11 referenced in the spec review log but absent from `deferred-work.md` — add entries.
- **Verification limits:** `biome check` / `tsc --noEmit` / Playwright runs are not reproducible in this dependency-less worktree; re-run in the parent repo before merge.

---

## Knowledge Base References Applied

- **test-levels-framework** - Config contract (unit-style) + E2E wiring as the correct levels for a test-infra chore (no production UI/API surface).
- **test-priorities-matrix / risk-governance** - P0-P3 ordering driven by the story risk register (WH-01 collection gap first; WH-02 env-required; WH-04 NODE_OPTIONS).
- **test-quality** - One assertion intent per test, explicit assertions in test bodies, deterministic env setup with serial mode, no placeholders.
- **api-testing-patterns / selector-resilience** - Playwright `request`/`page` fixtures; URL/body-level assertions instead of brittle selectors.

---

## Contact

- Tag @TEA in team standup
- Consult `./resources/knowledge` for testing best practices

---

**Generated by BMad TEA Agent** - 2026-08-08
