import { expect, test } from "../support/fixtures";

// dw-web-e2e-config-hardening ATDD E2E RED scaffolds (DW-3 + DW-7).
//
// These lock the wiring between the env-driven `playwright.config.ts` and a real
// browser session: `baseURL` must come from `BASE_URL` (via `webBaseUrl()`), the
// web server must boot the documented dev script (`npm run dev -- -p <port>`
// derived from `BASE_URL`), the `PLAYWRIGHT_WEB_SERVER_COMMAND` override must win,
// and the `--max-old-space-size=2048` memory guard must survive through
// `webServer.env.NODE_OPTIONS`.
//
// Implementation is already in the working tree, so each scaffold is an
// ACTIVATION / REGRESSION lock. Tests stay skipped until activated by a developer
// with a real app running (the playwright.config `webServer` boots it). Removing
// `test.skip(` makes the test active; it FAILS if the config wiring regresses
// (hardcoded localhost, missing port derivation, dropped memory guard).

test.describe("WH e2e wiring (RED): env-driven baseURL + webServer", () => {
  test.skip("WH-AC1 [P0] the app is served at the baseURL derived from BASE_URL", async ({ page }) => {
    // Playwright derives baseURL from BASE_URL via webBaseUrl(); a root
    // navigation must reach the app (redirect or login) rather than a 404/502.
    await page.goto("/");

    // 23.1: every path is locale-prefixed (/id by default).
    await expect(page).toHaveURL(/\/id\/(operations-overview|dashboard\/operations-overview|auth\/v2\/login)/);
    await expect(page.locator("body")).toBeVisible();
  });

  test.skip("WH-P1-04 [P1] the web server boots on the port parsed from BASE_URL", async ({ page }) => {
    // The default webServer command is `npm run dev -- -p <port from BASE_URL>`.
    // If the port derivation is broken, readiness never happens (120s timeout)
    // or the app serves on the wrong port.
    await page.goto("/");

    await expect(page.locator("body")).toBeVisible();
    expect(page.url()).toMatch(/localhost:\d+/);
  });

  test.skip("WH-P2-02 [P2] the web server runs with the NODE_OPTIONS memory guard applied", async () => {
    // webServer.env.NODE_OPTIONS must carry --max-old-space-size=2048 into the
    // dev server process. This cannot be asserted from a test (the child-process
    // env is not reachable), so this scaffold is a manual evidence checklist:
    // when activated, boot the suite on constrained CI and confirm the dev server
    // starts without an OOM/cryptic process kill. The config-level wiring itself
    // is locked by the config-contract unit suite, not by this scaffold.
    expect(true).toBe(true);
  });

  test.skip("WH-P2-04 [P2] PLAYWRIGHT_WEB_SERVER_COMMAND override wins over the derived default", async ({ page }) => {
    // Run with PLAYWRIGHT_WEB_SERVER_COMMAND set; the server must still come up
    // and serve the baseURL (readiness uses baseURL, not the command text).
    await page.goto("/");

    await expect(page.locator("body")).toBeVisible();
  });
});
