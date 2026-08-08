---
status: done
---

# bmad-dev-auto-result: dw-web-e2e-config-hardening (TEA automate run)

## Summary

TEA `bmad-testarch-automate` workflow for the `dw-web-e2e-config-hardening` deferred-work bundle (DW-3/DW-7: env-driven Playwright config). The working tree already carried the config change (config.ts, playwright.config.ts, syncro-api-client.ts, .env.example, README) plus its verified probes; this automate run added the verification layer the test-design flagged as missing and closed the headline risk (WH-01/DW-10).

## What was done

- Added `syncro/apps/web/tests/support/config.test.ts` — 11 unit tests for `webBaseUrl`/`apiBaseUrl`/`requireEnv`/`requireUrl` (env resolution, port extraction, `/api/v1` suffix enforcement, trailing-slash normalization, descriptive error text). GREEN (11/11).
- Added `syncro/apps/web/playwright.api.config.ts` — dedicated config collecting the `tests/api` suites (audit-log contract, spareparts, installations), no webServer (API tests use the `request` fixture).
- Added `syncro/apps/web/tests/support/check-api-collection.mjs` + `npm run test:api:check` — CI coverage-gap signal: exits non-zero if no `tests/api` spec is collected.
- Added `npm run test:api` script (`playwright test --config=playwright.api.config.ts`).
- Updated `syncro/apps/web/vitest.config.ts` (include `tests/support/**/*.test.{ts,tsx}`), `syncro/apps/web/tests/README.md` (test:api/test:api:check + DW-11 portless-BASE_URL pairing).
- Proved and replaced the naive `test:api` approach (`playwright test tests/api` fails against testDir `./tests/e2e`) with the working dedicated-config approach — the single highest-value verification fix for the bundle.
- Closed DW-10 in the ledger (API suites now enumerated + gated); added DW-11 (portless BASE_URL readiness hang) as open with the documented `PLAYWRIGHT_SKIP_WEB_SERVER=1` mitigation.

## Verification

- `npx vitest run tests/support/config.test.ts` → 11/11 PASS; full `npx vitest run` → 22 passed, 7 skipped (no regression).
- `npx playwright test --list` (env set) → config loads, e2e suite enumerated.
- `npx playwright test --config=playwright.api.config.ts --list` → 50 tests in 4 files.
- `node tests/support/check-api-collection.mjs` → PASS (exit 0) with WH-01 WARNING.
- `npx tsc --noEmit` PASS; `npx biome check` clean; `npm run test:api:check` exit 0.

## Open items (documented, not blockers)

- DW-11: portless `BASE_URL` with web server enabled hangs readiness until the 120s timeout; mitigation documented in tests/README.md (pair with `PLAYWRIGHT_SKIP_WEB_SERVER=1`).
- WH-02: env-required contract is intentionally fail-fast — CI must export `BASE_URL`/`API_URL`.
- WH-04: `webServer.env.NODE_OPTIONS` replaces (not merges) operator `NODE_OPTIONS`; pre-existing.
- CI wiring of `npm run test:api` + `npm run test:api:check` is recommended next workflow.
