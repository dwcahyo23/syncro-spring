import { expect, test } from "../support/fixtures";

// Story 23.1: next-intl locale routing (/id default, /en; localePrefix always).
test.describe("i18n locale routing", () => {
  test("visiting / redirects to the default locale id", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/id(\/|$|\?)/);
  });

  test("visiting /en renders the shell with lang=en", async ({ page }) => {
    await page.goto("/en");
    await expect(page.locator("html")).toHaveAttribute("lang", "en");
    // Unauthenticated: the guard keeps redirecting into the login shell under /en.
    await expect(page).toHaveURL(/\/en\/auth\/v2\/login/);
  });

  test("visiting /id renders the shell with lang=id", async ({ page }) => {
    await page.goto("/id");
    await expect(page.locator("html")).toHaveAttribute("lang", "id");
  });

  test("unauthenticated protected routes redirect to login with a locale-qualified next", async ({ page }) => {
    await page.goto("/id/alerts");
    await expect(page).toHaveURL(/\/id\/auth\/v2\/login\?next=%2Fid%2Falerts/);
  });

  // AC2 spot-check across the rewrite/redirect table, not just one URL: every
  // previously-public unprefixed path must land under /id (page or login redirect),
  // never 404/500 and never lose the locale prefix.
  for (const publicPath of [
    "/operations-overview",
    "/dashboard",
    "/alerts",
    "/analytics",
    "/settings",
    "/master-data/machines",
  ]) {
    test(`previously-public ${publicPath} resolves under /id`, async ({ page }) => {
      const response = await page.goto(publicPath);
      expect(response?.status()).toBeLessThan(500);
      await expect(page).toHaveURL(/\/id(\/|\?|$)/);
    });
  }

  test("unsupported locale prefixes return 404, not the default locale", async ({ page }) => {
    // The branded not-found UI hydrates client-side from the streamed 404 shell.
    // Story 23-2 P4: the [locale] not-found boundary renders from the active
    // catalog (middleware normalizes /fr/... under the default locale `id`),
    // so the shell copy is Indonesian here; the status code is the contract.
    const response = await page.goto("/fr/alerts");
    expect(response?.status()).toBe(404);
    await expect(page.locator("h1")).toContainText("Halaman tidak ditemukan.");
  });

  // Story 23-2 AC1: the unauthenticated login surface renders localized copy.
  test("/id login page renders Indonesian sign-in copy", async ({ page }) => {
    await page.goto("/id/auth/v2/login");
    await expect(page.getByRole("button", { name: "Masuk" })).toBeVisible();
  });

  test("/en login page renders English sign-in copy", async ({ page }) => {
    await page.goto("/en/auth/v2/login");
    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  });
});
