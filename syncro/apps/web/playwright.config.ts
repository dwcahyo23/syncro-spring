import { defineConfig, devices } from "@playwright/test";

const baseURL = process.env.BASE_URL ?? "http://localhost:3001";
const webServerCommand =
  process.env.PLAYWRIGHT_WEB_SERVER_COMMAND ??
  "node --max-old-space-size=2048 ./node_modules/next/dist/bin/next dev -p 3001";
const runAllBrowsers = Boolean(process.env.PLAYWRIGHT_ALL_BROWSERS);
const isCi = Boolean(process.env.CI);

export default defineConfig({
  testDir: "./tests/e2e",
  globalTimeout: isCi ? 20 * 60_000 : 8 * 60_000,
  timeout: 45_000,
  expect: { timeout: 7_500 },
  fullyParallel: false,
  forbidOnly: isCi,
  retries: isCi ? 2 : 0,
  workers: isCi ? 2 : 1,
  reporter: [["list"], ["html", { open: "never" }], ["junit", { outputFile: "test-results/e2e-junit.xml" }]],
  preserveOutput: "failures-only",
  use: {
    baseURL,
    actionTimeout: 10_000,
    navigationTimeout: 20_000,
    trace: isCi ? "retain-on-failure" : "off",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
    ...(runAllBrowsers
      ? [
          {
            name: "firefox",
            use: { ...devices["Desktop Firefox"] },
          },
          {
            name: "webkit",
            use: { ...devices["Desktop Safari"] },
          },
        ]
      : []),
  ],
  webServer: process.env.PLAYWRIGHT_SKIP_WEB_SERVER
    ? undefined
    : {
        command: webServerCommand,
        url: baseURL,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
      },
});
