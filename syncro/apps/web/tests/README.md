# Syncro Web Playwright Tests

## Setup

Use Node `24.15.0` from `.nvmrc`, then install dependencies from `syncro/apps/web`:

```bash
npm install
npx playwright install
```

Use `.env.example` as a reference when local values differ. Playwright does not auto-load `.env`; export the variables in your shell or configure them in CI. The browser e2e suites require `BASE_URL` (web base URL); the API suites require `API_URL` (backend base URL, includes `/api/v1`). `PLAYWRIGHT_WEB_SERVER_COMMAND` overrides the default web server command (`npm run dev -- -p <port from BASE_URL>`).

The legacy `SYNCRO_API_BASE_URL` variable (host without the base path) has been replaced by `API_URL` (which must include `/api/v1`); existing setups exporting it must switch to `API_URL`. API suites (`npm run test:api`) only need `API_URL`; `BASE_URL` is required for the browser e2e suites.

## Commands

```bash
npm run test:e2e
npm run test:e2e:headed
npm run test:e2e:ui
npm run test:e2e:report
npm run test:api
npm run test:api:check
```

Use `PLAYWRIGHT_SKIP_WEB_SERVER=1` when testing against an already running app. Override startup with `PLAYWRIGHT_WEB_SERVER_COMMAND` when needed.

`npm run test:api` runs the API suites under `tests/api/` (audit-log contract, spareparts, installations). Those suites are token-gated and largely `test.skip` by default, so a run can be green with zero executed tests. `npm run test:api:check` is the CI coverage-gap signal: it runs `playwright test --list` and warns on stderr (without failing the exit code) whenever the default run collects no `tests/api` spec — the known `testDir: ./tests/e2e` collection gap (DW-10) — and fails non-zero only when the suites cannot be enumerated even via the explicit `test:api` config. A failing default probe (e.g. stale `BASE_URL`) is treated as a warning and does not fail the check. Run `test:api:check` after `test:api` in CI and inspect the warning.

**Portless `BASE_URL` (DW-11 limitation):** when `BASE_URL` carries no port (e.g. a deployed origin like `https://app.example.com`), the derived web server command degrades to plain `npm run dev` (Next default port 3000) while the web server readiness URL is the portless origin — the server never reaches readiness and the run hangs until the 120s timeout. Pair a portless `BASE_URL` with `PLAYWRIGHT_SKIP_WEB_SERVER=1` so Playwright targets the already-running origin without booting a local server.

## Structure

- `e2e/` stores browser specs in Given/When/Then style.
- `support/fixtures/` exports project fixtures and `expect`.
- `support/helpers/` stores API clients, data factories, and network helpers.
- `support/page-objects/` is reserved for stable reusable page flows.

## Practices

- Prefer role, label, text, and `data-testid` selectors over CSS structure.
- Keep tests isolated; each spec owns its data and network mocks.
- Use factories for request and UI data, with explicit overrides for business cases.
- Assert user-visible outcome plus backend/API evidence when possible.
- Keep API response shapes aligned with generated OpenAPI types.

## CI notes

Playwright config writes HTML and JUnit reports to ignored artifact folders. CI should run `npm run test:e2e` after app dependencies and browsers are installed.

## Knowledge references

Applied TEA patterns: test levels, priority matrix, data factories, selective testing, CI burn-in, and test quality guidance.
