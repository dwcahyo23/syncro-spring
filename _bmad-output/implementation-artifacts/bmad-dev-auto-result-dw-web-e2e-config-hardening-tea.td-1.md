---
status: done
---

TEA test-design workflow (`bmad-testarch-test-design`) completed for `dw-web-e2e-config-hardening`.

## What was produced

- **Risk assessment + risk-based coverage strategy**: `_bmad-output/test-artifacts/test-design-story-web-e2e-config-hardening.md`
- **Workflow progress log** (story-level run appended): `_bmad-output/test-artifacts/test-design-progress.md`

## Scope covered

Working-tree changes for the `dw-web-e2e-config-hardening` bundle (baseline `caf1262` → uncommitted working tree):
- New `tests/support/config.ts` (lazy `webBaseUrl()`/`apiBaseUrl()`, descriptive errors, `/api/v1` suffix enforcement, trailing-slash normalization)
- `playwright.config.ts` env-driven base URL + derived `npm run dev -- -p <port>` web server command, `PLAYWRIGHT_WEB_SERVER_COMMAND` override, `webServer.env.NODE_OPTIONS` memory guard
- API helpers/specs switched to shared `apiBaseUrl()`; `.env.example`/README documentation
- DW-3/DW-7 deferred-work ledger close-out

## Key findings

- 9 risks scored (1 high at score 6: default `testDir: ./tests/e2e` does not collect `tests/api/*.spec.ts`, so the bundle's primary `apiBaseUrl()` consumers are silently omitted — deferred DW-10).
- P0 coverage (config-load + error contract probes) already verified in the spec; 0 incremental effort. P1 gaps: API-suite collection, `config.ts` vitest unit suite, live audit-log API run, e2e wiring.
- Gap found: DW-10/DW-11 are referenced as "deferred to ledger" in the spec review log but are NOT present in `deferred-work.md` — ledger entries need to be added.
- No production code was modified. Artifacts written only under TEA's configured `test_artifacts` directory.

## Recommended follow-ups (recorded in the design)

1. Add DW-10/DW-11 entries to the deferred-work ledger.
2. Collect `tests/api/**` so the API suites actually run in CI.
3. Add the `config.ts` vitest unit suite (new module has zero unit tests).
4. Decide `NODE_OPTIONS` append-vs-replace and document.
5. Re-run `biome check` + `tsc --noEmit` in the parent repo before merge (not possible in this dependency-less worktree).
