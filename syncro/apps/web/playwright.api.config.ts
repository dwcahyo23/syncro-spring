import { defineConfig } from "@playwright/test";

// Dedicated config for the API suites under `tests/api/` (WH-01 / DW-10).
//
// The base `playwright.config.ts` uses `testDir: ./tests/e2e`, so positional
// args like `tests/api` never match and the API suites (audit-log contract,
// spareparts, installations — the primary `apiBaseUrl()` consumers) are never
// collected by `npm run test:e2e` or `playwright test --list`. This config
// collects them without changing the base config's projects/reporters.
//
// Run via `npm run test:api`. API tests use the `request` fixture and build
// absolute URLs from `apiBaseUrl()`; they never navigate a browser, so no
// `use.baseURL` is set and `BASE_URL` is not required here — an API-only CI
// job needs only `API_URL`. No `webServer` is configured either.
export default defineConfig({
  testDir: "./tests/api",
  globalTimeout: process.env.CI ? 20 * 60_000 : 8 * 60_000,
  timeout: 45_000,
  expect: { timeout: 7_500 },
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  // Single worker: the config-contract scaffold mutates `process.env` and must
  // run serially with the other API suites once activated (WH-AC4 env races).
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }], ["junit", { outputFile: "api-junit.xml" }]],
  preserveOutput: "failures-only",
  // Dedicated output dir: Playwright clears the whole outputDir at the start of
  // every run, so sharing the default test-results with the base config would
  // wipe the API junit/report whenever CI runs test:e2e after test:api.
  outputDir: "test-results/api",
  use: {
    actionTimeout: 10_000,
    navigationTimeout: 20_000,
    trace: process.env.CI ? "retain-on-failure" : "off",
  },
});
