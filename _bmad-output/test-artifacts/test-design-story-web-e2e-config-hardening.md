---
workflowStatus: 'draft'
runId: '20260808-012337-779e'
story: 'dw-web-e2e-config-hardening'
storyKey: 'dw-web-e2e-config-hardening'
baselineRevision: 'caf12629fb344ffe7be5130c32948a85e488a7da'
finalRevision: 'uncommitted (working tree)'
lastSaved: '2026-08-08'
---

# Test Design: Story dw-web-e2e-config-hardening - Env-driven Playwright config (DW-3 + DW-7)

**Date:** 2026-08-08
**Author:** Yusuf (TEA / bmad-loop story test-design run)
**Status:** Draft
**Mode:** Story-level (deferred-work sweep bundle; DW-3 + DW-7). Companion to epic-level design in `test-design-epic-2.md`.

---

## 1. Executive Summary

**Scope:** `dw-web-e2e-config-hardening` is a test-infrastructure chore. It externalizes and hardens the web Playwright config: a single lazy env-driven config source (`tests/support/config.ts`) resolves `BASE_URL` (web) and `API_URL` (backend, includes `/api/v1`), `playwright.config.ts` derives the base URL and default web server command from `BASE_URL`, and every API helper/spec consumes the shared `apiBaseUrl()`. No application runtime code changes; no production behavior changes.

**What the working tree contains (evidence base):**

- `syncro/apps/web/tests/support/config.ts` — NEW: lazy `webBaseUrl()`/`apiBaseUrl()`; `requireEnv` (trim), `requireUrl` (URL validity), `/api/v1` suffix enforcement, trailing-slash normalization, module-resolved `.env.example` pointer in error text.
- `syncro/apps/web/playwright.config.ts` — `baseURL` from `webBaseUrl()`; default `webServerCommand` = `npm run dev -- -p <port from BASE_URL>` (portless or port `0` → plain `npm run dev`); `PLAYWRIGHT_WEB_SERVER_COMMAND?.trim() || default` override; `webServer.env.NODE_OPTIONS = "--max-old-space-size=2048"` (memory guard restored).
- `syncro/apps/web/tests/support/helpers/syncro-api-client.ts` — `apiBaseUrl()` replaces `API_URL ?? "http://localhost:8080/api/v1"`.
- `syncro/apps/web/tests/api/audit-log.spec.ts`, `tests/api/spareparts-atdd.spec.ts`, `tests/api/machine-sparepart-installations-atdd.spec.ts` — lazy `apiBaseUrl()` call sites; no `localhost` literal remains.
- `syncro/apps/web/.env.example` — `BASE_URL`/`API_URL` documented as required; `PLAYWRIGHT_WEB_SERVER_COMMAND` shown as commented opt-in.
- `syncro/apps/web/tests/README.md` — env contract, no auto-`.env`, override doc.
- `_bmad-output/implementation-artifacts/spec-web-e2e-config-hardening.md` — intent contract, I/O edge-case matrix, review log, verification.
- `_bmad-output/implementation-artifacts/deferred-work.md` — DW-3/DW-7 closed as resolved by this bundle (uncommitted edit in the working tree).

**Risk summary:**

- Total risks identified: 9
- High-priority risks (>=6): 1 (API suites not collected by the default `testDir` — pre-existing, but it silently omits the bundle's primary config consumers)
- Critical categories: TECH (collection gap, lazy-skip discoverability), OPS (env-required breaking change, NODE_OPTIONS clobber), PERF (memory guard survival)

**Coverage summary:**

- P0 scenarios: 5 (config-load + error contract probes — already verified in the spec; re-run as the gate, ~0 new effort)
- P1 scenarios: 4 (~6-10 h, incl. API-suite enumeration + config.ts unit suite)
- P2/P3 scenarios: 7 (~4-8 h, incl. edge-matrix probes + multi-browser smoke)
- **Total effort**: ~10-18 h incremental (~1.5-2.5 days), test-infra-only

**Bottom line:** The change itself is small, well-documented, and its core contract was already verified (`playwright test --list` under env set/unset, descriptive-error probes, grep clean). The dominant residual risks are *verification blind spots*, not correctness: the API suites — the primary consumers of the new `apiBaseUrl()` — are not collected by the default runner (`testDir: ./tests/e2e`, deferred DW-10), so CI can be green with zero API-suite execution, and a portless `BASE_URL` with web server enabled still hangs on readiness (deferred DW-11). Both are flagged as follow-ups; neither is a blocker for this chore.

---

## 2. Inputs Reviewed

- `_bmad-output/implementation-artifacts/spec-web-e2e-config-hardening.md` (intent contract, I/O matrix, review triage, verification)
- `_bmad-output/implementation-artifacts/deferred-work.md` (DW-3, DW-7 done; DW-10, DW-11 referenced as deferred in the spec review log but NOT yet in the ledger)
- `syncro/apps/web/tests/support/config.ts` (new)
- `syncro/apps/web/playwright.config.ts` (full file)
- `syncro/apps/web/tests/support/helpers/syncro-api-client.ts`
- `syncro/apps/web/tests/api/audit-log.spec.ts`, `tests/api/spareparts-atdd.spec.ts`, `tests/api/machine-sparepart-installations-atdd.spec.ts`
- `syncro/apps/web/tests/e2e/dashboard.spec.ts`, `tests/e2e/audit-log.atdd-red.spec.ts`
- `syncro/apps/web/tests/support/fixtures/index.ts`, `tests/README.md`, `.env.example`, `package.json` scripts
- Config: `_bmad/tea/config.yaml` (`test_artifacts: _bmad-output/test-artifacts`, `risk_threshold: p1`)

**Existing test coverage (test-infra surface):**

- `tests/e2e/dashboard.spec.ts` — 1 browser test (root redirect); the only spec collected by default `testDir: ./tests/e2e` besides the skipped RED-phase e2e scaffold.
- `tests/e2e/audit-log.atdd-red.spec.ts` — 5 RED-phase E2E cases, all `test.skip`.
- `tests/api/audit-log.spec.ts` — 9 live client-contract cases, token-gated (`SYNCRO_ATDD_*_TOKEN`), NOT collected by default `testDir`.
- `tests/api/spareparts-atdd.spec.ts`, `tests/api/machine-sparepart-installations-atdd.spec.ts` — RED-phase API scaffolds (majority `test.skip`), NOT collected by default `testDir`.
- No unit tests exist for `tests/support/config.ts`.

**Verification already recorded in the spec (not re-run here):** `playwright test --list` with env set (config loads, e2e suite enumerated); `--list` with `BASE_URL` unset (descriptive error naming var + resolved `.env.example` path); malformed `BASE_URL` (descriptive error); `API_URL` trailing-slash normalized; `API_URL` without `/api/v1` (descriptive error); empty `PLAYWRIGHT_WEB_SERVER_COMMAND` falls back; grep clean for `localhost:8080`/`localhost:3001`/`next/dist/bin/next`/`SYNCRO_API_BASE_URL` in `playwright.config.ts` and `tests/**`. `biome check`/`tsc --noEmit` NOT run (no `node_modules` in worktree).

---

## 3. Risk Assessment

Score = Probability x Impact (1-3 each). Priority threshold per config: `risk_threshold: p1`.

| ID | Category | Risk | P | I | Score | Priority |
|---|---|---|---|---:|---:|---:|---|---|
| WH-01 | TECH | Default `testDir: ./tests/e2e` does NOT collect `tests/api/*.spec.ts`; the API suites (audit-log, spareparts, installations — the bundle's primary `apiBaseUrl()` consumers) are silently omitted from `npm run test:e2e` and `playwright test --list`. CI can pass with zero API-suite execution. **Pre-existing** config scope (DW-10), but it directly weakens verification of this change. | 3 | 2 | 6 | **P1** |
| WH-02 | OPS | Env-required contract is a breaking change from silent localhost fallbacks: `playwright.config.ts` now calls `webBaseUrl()` eagerly, so any operator/CI that previously ran tests with no env now fails hard at config load. Intentional fail-fast, but a CI pipeline not updated to export `BASE_URL`/`API_URL` blocks the test stage (and `.env` is never auto-loaded). | 2 | 2 | 4 | P1 |
| WH-03 | TECH | Portless `BASE_URL` (deployed origin) with webServer enabled hangs on readiness: derived command is plain `npm run dev` (Next default port 3000) while `webServer.url` is the portless origin → 120s timeout. **Pre-existing** (DW-11); new derivation keeps the mismatch. Only documented pairing with `PLAYWRIGHT_SKIP_WEB_SERVER=1` avoids it. | 2 | 2 | 4 | P2 |
| WH-04 | OPS | `webServer.env.NODE_OPTIONS` hardcodes `--max-old-space-size=2048` and *replaces* (does not merge) any operator/CI-provided `NODE_OPTIONS` (e.g. OpenSSL legacy-provider flags), silently dropping them for the server process. | 2 | 2 | 4 | P2 |
| WH-05 | TECH | Silent-skip discoverability: with `API_URL` unset, API suites are token-gated/`test.skip` and (per WH-01) not collected anyway; the suite can be fully green with zero API coverage and no signal. Lazy resolution is correct, but nothing surfaces the gap at CI time. | 2 | 2 | 4 | P2 |
| WH-06 | PERF | Memory guard must actually survive under `npm run dev` via `webServer.env`. If env merge/pass-through misbehaves on the target platform (or the guard flag conflicts), Next dev can OOM on constrained CI with a cryptic process kill instead of a descriptive config error. | 1 | 3 | 3 | P2 |
| WH-07 | TECH | `PLAYWRIGHT_WEB_SERVER_COMMAND` override silently wins with a possibly stale port (operator uncomments `-p 3001` from the example while `BASE_URL` points elsewhere) → URL/readiness mismatch with no validation. | 2 | 1 | 2 | P3 |
| WH-08 | TECH | Command semantics changed from `node next/dist/bin/next dev` to `npm run dev`. The derived command now inherits whatever the `dev` script becomes (env prefix, build step, husky pre-script); the config no longer pins the exact invocation. | 1 | 2 | 2 | P3 |
| WH-09 | TECH | `new URL(baseURL).port` edge cases: default ports are stripped (e.g. `BASE_URL=http://app:80` → port `""` → no `-p`, server runs on 3000, readiness on :80 hangs); IPv6 `[::1]:3001` parses to `3001` but is unproven. | 1 | 2 | 2 | P3 |

### Risk Testability Notes

- Every risk above is testable with cheap probes: unit-level tests of `tests/support/config.ts` (pure functions), `playwright test --list` under an env matrix, and grep-based static guards. No browser or backend needed for the config-contract layer.
- WH-01 is the headline risk: it is deterministic (the suites never run by default), and fixing it is the single highest-value verification action for this bundle.
- WH-02/WH-04/WH-06 are operational (CI/process) risks; validation is a preflight check + a merge probe, not a functional test.
- WH-07/WH-08/WH-09 are low-consequence edge cases; document + one probe each, no blockers.

---

## 4. Risk-Based Coverage Strategy

Prioritization: config-load contract first (already verified — re-run as the gate), then close the collection gap and add unit tests, then edge-matrix probes.

### P0 - Critical (must pass; already verified in the spec — verify, do not re-build)

| ID | Scenario | Level | Evidence |
|---|---|---|---|
| T-WH-P0-01 | `BASE_URL` set → `webBaseUrl()` returns normalized URL (trailing slash stripped); config loads; `baseURL`/`webServer.url` equal `BASE_URL`; default command = `npm run dev -- -p <port>` | Config probe / unit | spec Verification (PASS) |
| T-WH-P0-02 | `BASE_URL` unset → config load aborts with descriptive error naming `BASE_URL` and the module-resolved `.env.example` path | Config probe | spec Verification (PASS) |
| T-WH-P0-03 | `API_URL` unset → module still imports (lazy); skipped API specs skip cleanly; `apiBaseUrl()` throws descriptive error only at call site | Unit / probe | spec Verification (PASS) |
| T-WH-P0-04 | `API_URL` without `/api/v1` suffix, malformed `BASE_URL`, `API_URL` trailing slash, empty/whitespace `PLAYWRIGHT_WEB_SERVER_COMMAND` → each resolves to the documented behavior (descriptive errors / normalized fallback) | Unit / probe | spec Verification (PASS) |
| T-WH-P0-05 | grep guard: no `http://localhost:8080`, `http://localhost:3001`, `next/dist/bin/next`, or `SYNCRO_API_BASE_URL` in `playwright.config.ts` or `tests/**` | Static | spec Verification (PASS) |

**P0 gate:** re-run the five probes above (commands below). 100% pass required.

### P1 - High (incremental work to close the real gaps)

| ID | Scenario | Level | Gap closed | Effort |
|---|---|---|---|---|
| T-WH-P1-01 | **API-suite collection**: enumerate and run `tests/api/*.spec.ts` — add a `test:api` npm script (`playwright test tests/api`) or a second project/testDir, so `apiBaseUrl()` consumers (audit-log contract, spareparts/installations scaffolds) are collected, listed, and executed in CI. | API / config | WH-01 | 2-4h |
| T-WH-P1-02 | **config.ts unit suite**: vitest tests for `requireEnv`/`requireUrl`/`webBaseUrl`/`apiBaseUrl` covering trim, URL validity, `/api/v1` suffix, trailing-slash normalization, portless/port-0 handling, and error text contents. | Unit | WH-02, WH-09 | 2-3h |
| T-WH-P1-03 | **Live API run**: run `audit-log.spec.ts` against a live backend with `SYNCRO_ATDD_*_TOKEN` set (GET `/api/v1/audit-log` contract over real HTTP) — proves the `apiBaseUrl()` path end-to-end. | API | WH-05 | 1-2h |
| T-WH-P1-04 | **E2E wiring**: run `dashboard.spec.ts` against the derived webServer — proves `baseURL` + `webServer` command + readiness actually serve the app. | E2E | WH-06, WH-08 | 1h |

### P2 - Medium (deferred unless evidence is cheap)

| ID | Scenario | Level | Gap closed | Effort |
|---|---|---|---|---|
| T-WH-P2-01 | Portless `BASE_URL` + `PLAYWRIGHT_SKIP_WEB_SERVER=1` pairing probe; document the DW-11 hang as a known limitation with the skip-server escape hatch. | Config probe | WH-03 | 0.5h |
| T-WH-P2-02 | `NODE_OPTIONS` merge probe: run the webServer with an operator-provided `NODE_OPTIONS` and confirm whether it is preserved or replaced; decide append-vs-replace and document. | Config probe | WH-04 | 0.5h |
| T-WH-P2-03 | CI coverage-gap signal: a check that asserts the API suites were collected (e.g. `--list | grep tests/api` non-empty) so a green-but-empty API run fails loudly. | CI/static | WH-05 | 0.5-1h |
| T-WH-P2-04 | Override matrix probe: `PLAYWRIGHT_WEB_SERVER_COMMAND` set wins; empty and whitespace values fall back to the derived default. | Config probe | WH-07 | 0.5h |

### P3 - Low / Edge

| ID | Scenario | Level | Effort |
|---|---|---|---|
| T-WH-P3-01 | `PLAYWRIGHT_ALL_BROWSERS=1` smoke (chromium + firefox + webkit projects load and enumerate). | E2E smoke | 0.5h |
| T-WH-P3-02 | URL-parse probes: default-port `:80`/`:443` URLs and IPv6 `[::1]:3001` `BASE_URL` values. | Unit | 0.5h |
| T-WH-P3-03 | README/.env.example doc accuracy review against the implemented contract (incl. the commented-out override example). | Docs | 0.5h |

---

## 5. Traceability to Acceptance Criteria (spec)

| AC (from spec) | Covered by |
|---|---|
| AC1: `BASE_URL` set → `baseURL`/`webServer.url` equal it; default command is `npm run dev -- -p <port>`; no hardcoded port or `next/dist/bin/next` | T-WH-P0-01, T-WH-P0-05 |
| AC2: `BASE_URL` unset → fast descriptive error naming `BASE_URL` + `.env.example` | T-WH-P0-02 |
| AC3: `API_URL` set → helpers/specs target it; no `http://localhost:8080` in test source | T-WH-P0-04, T-WH-P0-05, T-WH-P1-03 |
| AC4: `API_URL` unset → module loads, skipped specs skip; live call throws descriptive error | T-WH-P0-03 |
| AC5: `biome check`, `tsc --noEmit`, `playwright test --list` (env set) all pass | T-WH-P1-01, T-WH-P1-02 (unit suite), spec note: biome/tsc unverifiable without `node_modules` |

**Unmapped / partial:**
- `biome check` / `tsc --noEmit` acceptance not reproducible in this worktree (no `node_modules`) — must be re-run in the parent repo with deps installed before merge.
- "Full spec set is enumerated" claim is only true once `tests/api/**` is collected (WH-01); the spec review already flags the enumeration claim as inaccurate under default `testDir`.
- DW-10/DW-11 are referenced as "deferred to ledger" in the spec review log but are NOT present in `deferred-work.md` (only DW-1..DW-9 there, DW-8/DW-9 open) — ledger entries need to be added.

---

## 6. NFR Planning (maintainability, reliability, operability in scope)

| NFR Category | In Scope? | Threshold | Risk Link | Planned Validation | Evidence Needed |
|---|---|---|---|---|---|
| Maintainability | Yes | Single env-driven config source; zero hardcoded ports/URLs in config or test source | WH-01, WH-05 | grep guard + `config.ts` unit suite | grep output, vitest report |
| Reliability (fail-fast) | Yes | Missing/invalid env fails fast with a descriptive error naming the variable and `.env.example` | WH-02 | `--list` probes + unit tests | probe output |
| Reliability (lazy skip) | Yes | Skipped RED-phase specs load and skip cleanly with no env; live calls throw only at use | WH-05 | unit + `--list` probes | probe output |
| Operability | Yes | CI/local portable; `PLAYWRIGHT_WEB_SERVER_COMMAND` override honored; `.env` not auto-loaded documented | WH-02, WH-07 | preflight check + override probe | CI log, probe output |
| Performance (memory guard) | Yes | `--max-old-space-size=2048` applies to the web server process under the derived command | WH-06 | `webServer.env.NODE_OPTIONS` probe + e2e run on constrained CI | probe output, e2e report |
| Security | No | No auth/data/code change; env vars are URLs, not secrets | - | n/a | n/a |

**Unknown thresholds (do not invent):** no startup-time, readiness, or OOM SLO exists for the Playwright web server; the performance verdict is limited to "memory guard survives and server boots", deferred to `nfr-assess` after evidence exists.

---

## 7. Execution Strategy

- **PR:** config-contract probes (P0 gate) + `config.ts` unit suite (T-WH-P1-02) + `dashboard.spec.ts` (T-WH-P1-04) — full functional set, well under 15 min with Playwright parallelization.
- **PR:** API-suite collection fix (T-WH-P1-01) once merged, so the API suites execute on every PR.
- **Nightly/Weekly:** live API run with real backend + tokens (T-WH-P1-03); multi-browser smoke (T-WH-P3-01).
- **Manual/ops:** preflight env check, `NODE_OPTIONS` merge decision, portless-BASE_URL documentation (T-WH-P2-01..03) confirmed before CI rollout.

## 8. Resource Estimates (ranges only)

- P0: ~0 h incremental (already verified in the spec; re-run as gate ≈ 0.5 h)
- P1: ~6-10 h (API collection fix + vitest unit suite + live API run + e2e wiring)
- P2: ~2-3 h (edge probes + CI coverage-gap signal)
- P3: ~1-2 h (multi-browser smoke + URL-parse probes + docs review)
- **Total:** ~10-18 h (~1.5-2.5 days), test-infra-only, no backend/frontend production impact

## 9. Quality Gates

- P0 pass rate = 100% (config-contract probes + grep guard)
- P1 pass rate >= 95%; new P1 items (API collection, unit suite, live run) pass or produce documented evidence
- High-risk mitigations: WH-01 decision documented before this bundle is fully risk-closed — either `tests/api/**` is collected in CI or the omission is accepted with a CI coverage-gap signal (T-WH-P2-03)
- Coverage target >= 80% on the config surface (I/O matrix rows all covered by P0/P1)
- NFR evidence: config probes + unit report + e2e run exist for MAINTAINABILITY/RELIABILITY/OPERABILITY; final PASS/CONCERNS/FAIL deferred to `nfr-assess`
- Pre-merge: `biome check` + `tsc --noEmit` re-run in the parent repo (unverifiable in this worktree)

## 10. Interworking & Regression

| Component | Impact | Regression scope |
|---|---|---|
| `tests/api/audit-log.spec.ts` | `apiBaseUrl()` call sites; token-gated live contract suite | `npx playwright test tests/api/audit-log.spec.ts` with tokens |
| `tests/api/spareparts-atdd.spec.ts`, `machine-sparepart-installations-atdd.spec.ts` | `apiBaseUrl()` call sites; RED-phase scaffolds (skipped) | `npx playwright test tests/api/` (collection fix) |
| `tests/support/helpers/syncro-api-client.ts` | Consumed by `support/fixtures/index.ts` (`api` fixture); used by e2e/audit-log specs | `dashboard.spec.ts` + audit-log specs |
| `tests/e2e/dashboard.spec.ts` | `baseURL` + derived `webServer` command + readiness | `npm run test:e2e` |
| `.env.example`, `tests/README.md` | Operator env contract | Doc review (T-WH-P3-03) |
| `playwright.config.ts` | Projects, reporters, retries, `PLAYWRIGHT_SKIP_WEB_SERVER`, `PLAYWRIGHT_ALL_BROWSERS` unchanged per spec | `--list` under env matrix |
| Deferred-work ledger | DW-3/DW-7 marked resolved | None (ledger close-out); add DW-10/DW-11 entries |
| Backend MockMvc tests (`AuditLogControllerTest`, etc.) | Unchanged; audit-log spec re-verifies the same contract over HTTP | Existing backend suites unaffected |

---

## 11. Recommended Follow-Up Work (gaps discovered)

1. **Add DW-10 and DW-11 to the deferred-work ledger** — the spec review log says they were deferred, but `deferred-work.md` contains no DW-10/DW-11 entries (only DW-1..DW-9).
2. **Collect the API suites** (T-WH-P1-01): `test:api` script or second project/testDir, so the bundle's primary config consumers actually run in CI.
3. **Add the `config.ts` vitest unit suite** (T-WH-P1-02) — the new module has zero unit tests today.
4. **Decide `NODE_OPTIONS` append-vs-replace** (WH-04) and document; the current hardcode drops operator flags for the server process.
5. **Add a CI coverage-gap signal** (T-WH-P2-03) so a green-but-empty API run fails loudly.
6. **Re-run `biome check` + `tsc --noEmit`** in the parent repo before merge (not possible in this dependency-less worktree).

## 12. Verification Commands

- `npm --prefix syncro/apps/web exec playwright test --list` with `BASE_URL=http://localhost:3001` and `API_URL=http://localhost:8080/api/v1` set — PASS (config loads; e2e suite enumerated; API suites omitted until T-WH-P1-01)
- `npm --prefix syncro/apps/web exec playwright test --list` with `BASE_URL` unset — PASS (descriptive error naming `BASE_URL` + resolved `.env.example` path)
- `npx vitest run` for the new `config.ts` unit suite (after T-WH-P1-02)
- `grep -rE "http://localhost:8080|http://localhost:3001|next/dist/bin/next|SYNCRO_API_BASE_URL" syncro/apps/web/playwright.config.ts syncro/apps/web/tests` — PASS (no matches; verified in spec)
- Parent repo (with `node_modules`): `npm --prefix syncro/apps/web run check` and `npx --prefix syncro/apps/web tsc --noEmit`

**Generated by**: BMad TEA Agent - Test Architect Module
**Workflow**: `bmad-testarch-test-design` (story-level run)
