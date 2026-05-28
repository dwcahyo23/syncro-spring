# Syncro Web Playwright Tests

## Setup

Use Node `24.15.0` from `.nvmrc`, then install dependencies from `syncro/apps/web`:

```bash
npm install
npx playwright install
```

Copy `.env.example` when local values differ. `BASE_URL` controls browser navigation; `API_URL` controls API helper calls.

## Commands

```bash
npm run test:e2e
npm run test:e2e:headed
npm run test:e2e:ui
npm run test:e2e:report
```

Use `PLAYWRIGHT_SKIP_WEB_SERVER=1` when testing against an already running app. Override startup with `PLAYWRIGHT_WEB_SERVER_COMMAND` when needed.

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
