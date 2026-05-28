---
stepsCompleted: ['step-01-preflight', 'step-02-select-framework', 'step-03-scaffold-framework', 'step-04-docs-and-scripts', 'step-05-validate-and-summary']
lastStep: 'step-05-validate-and-summary'
lastSaved: '2026-05-28'
inputDocuments:
  - _bmad/tea/config.yaml
  - _bmad-output/project-context.md
  - syncro/apps/web/package.json
  - syncro/apps/backend/pom.xml
  - _bmad-output/planning-artifacts/architecture.md
---

# Test Framework Setup Progress

## Step 1: Preflight

Detected stack: `fullstack`, but user narrowed scope to frontend-only framework scaffolding for `syncro/apps/web` because backend already has Spring/JUnit tests under `syncro/apps/backend/src/test`.

Frontend context:
- App: Next.js 16 / React 19 / TypeScript 5.
- Package manager: npm with `package-lock.json`.
- Existing E2E framework: none (`playwright.config.*`, `cypress.config.*`, `cypress.json` not found under `syncro/`).

Backend context:
- App: Spring Boot 4.0.6 / Java 25 / Maven.
- Existing backend test suite present; no backend framework scaffold needed.

## Step 2: Framework Selection

Selected framework: Playwright.

Reasoning:
- Better fit for Next.js operational UI with API + browser integration checks.
- Multi-browser support and CI parallelism fit project verification rules.
- Playwright supports frontend-only E2E without changing existing backend JUnit/Testcontainers setup.

## Step 3: Scaffold Framework

Created frontend-only Playwright scaffold under `syncro/apps/web`:
- `playwright.config.ts` with 60s test timeout, 15s action timeout, 30s navigation timeout, HTML/JUnit/list reporters, failure artifacts, CI retries, and Chromium/Firefox/WebKit projects.
- `tests/e2e/` sample specs for dashboard reachability and API fixture usage.
- `tests/support/fixtures/`, `tests/support/helpers/`, and `tests/support/page-objects/` structure.
- `tests/support/helpers/data-factory.ts` with typed plant factory using Faker.
- `tests/support/helpers/syncro-api-client.ts` with Playwright API request helper.
- `.env.example`, `.nvmrc`, Playwright npm scripts, and ignored test artifact folders.

Validation run:
- `npm --prefix "syncro/apps/web" run check` passed.
- `npm --prefix "syncro/apps/web" exec playwright test -- --list` found 2 tests.

Note: dependency install emitted EBADENGINE warning because local Node is `v24.11.1`; `.nvmrc` now pins `24.15.0` to satisfy dependency engine range.

## Step 4: Documentation & Scripts

Created `syncro/apps/web/tests/README.md` with setup, commands, architecture, selector/data practices, CI notes, and TEA references.

Added npm scripts:
- `test:e2e`
- `test:e2e:ui`
- `test:e2e:headed`
- `test:e2e:report`

## Step 5: Validate & Summary

Checklist validation:
- Preflight: passed for frontend-only scope.
- Directory structure: `tests/e2e`, `tests/support/fixtures`, `tests/support/helpers`, `tests/support/page-objects` present.
- Config correctness: Playwright TypeScript config present with required timeouts, env base URL, reporters, artifacts, parallelism, CI retries/workers.
- Fixtures/factories: Playwright fixture index, typed plant factory, API helper present.
- Docs/scripts: `tests/README.md` and npm scripts present.

Validation evidence:
- `npm --prefix "syncro/apps/web" run check` passed.
- `npm --prefix "syncro/apps/web" exec playwright test -- --config "syncro/apps/web/playwright.config.ts" --project=chromium --reporter=list` passed 2 tests.

Framework selected: Playwright.
Knowledge fragments applied: test levels, priority matrix, data factories, selective testing, CI burn-in, test quality, Playwright config/fixture patterns.
