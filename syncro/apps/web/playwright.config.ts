import { defineConfig, devices } from "@playwright/test";

import { webBaseUrl } from "./tests/support/config";

const baseURL = webBaseUrl();
const webServerPort = new URL(baseURL).port;
const defaultWebServerCommand =
  webServerPort && webServerPort !== "0" ? `npm run dev -- -p ${webServerPort}` : "npm run dev";
const webServerCommand = process.env.PLAYWRIGHT_WEB_SERVER_COMMAND?.trim() || defaultWebServerCommand;
// Append the memory guard only when the operator's NODE_OPTIONS does not already
// set a heap size; a duplicate --max-old-space-size would silently override a
// deliberately larger dev/CI heap (last flag wins).
const existingNodeOptions = process.env.NODE_OPTIONS ?? "";
const webServerNodeOptions = existingNodeOptions.includes("--max-old-space-size")
  ? existingNodeOptions
  : [existingNodeOptions, "--max-old-space-size=2048"].filter(Boolean).join(" ");
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
        env: {
          NODE_OPTIONS: webServerNodeOptions,
        },
      },
});
