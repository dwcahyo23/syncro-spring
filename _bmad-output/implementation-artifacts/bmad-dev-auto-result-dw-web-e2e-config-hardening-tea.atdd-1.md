---
status: done
---

TEA ATDD workflow (`bmad-testarch-atdd`) completed for `dw-web-e2e-config-hardening`.

## What was produced

- **Red-phase acceptance test scaffolds** (all `test.skip()`, TDD RED phase):
  - `syncro/apps/web/tests/api/config-contract.atdd-red.spec.ts` (11 tests) — serial config-contract probes for the new `tests/support/config.ts` module (trailing-slash strip, fail-fast BASE_URL/API_URL errors with `.env.example` pointer, malformed-URL rejection, whitespace trim, `/api/v1` suffix enforcement, lazy-skip import contract).
  - `syncro/apps/web/tests/e2e/config-hardening.atdd-red.spec.ts` (4 tests) — baseURL-from-BASE_URL wiring, port-parsed webServer boot, `NODE_OPTIONS` memory-guard evidence lock, `PLAYWRIGHT_WEB_SERVER_COMMAND` override matrix.
- **Implementation checklist**: `_bmad-output/test-artifacts/atdd-checklist-dw-web-e2e-config-hardening.md`
- **Workflow progress log** (ATDD run appended): `_bmad-output/test-artifacts/test-design-progress.md`

## Scope covered

Working-tree changes for the `dw-web-e2e-config-hardening` bundle (baseline `caf1262` → uncommitted working tree):
- New `tests/support/config.ts` (lazy `webBaseUrl()`/`apiBaseUrl()`, descriptive errors, `/api/v1` suffix enforcement, trailing-slash normalization)
- `playwright.config.ts` env-driven base URL + derived `npm run dev -- -p <port>` web server command, `PLAYWRIGHT_WEB_SERVER_COMMAND` override, `webServer.env.NODE_OPTIONS` memory guard
- API helpers/specs switched to shared `apiBaseUrl()`; `.env.example`/README documentation
- DW-3/DW-7 deferred-work ledger close-out

## Key findings

- Scaffolds are activation/regression locks on an already-implemented bundle: expected green today; RED if the config contract or wiring regresses.
- Flagship gap: `tests/api/**` is NOT collected by the default `testDir: ./tests/e2e` (WH-01/DW-10) — even the new config-contract spec is outside default collection; checklist carries the collection fix.
- DW-10/DW-11 referenced in the spec review log but missing from `deferred-work.md`; `NODE_OPTIONS` append-vs-replace decision (WH-04) unrecorded — both in checklist.
- Scaffolds NOT executed and `biome check`/`tsc --noEmit` not runnable in this dependency-less worktree (no `node_modules`); re-run in the parent repo before merge.
