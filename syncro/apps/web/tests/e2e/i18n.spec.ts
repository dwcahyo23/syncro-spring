import { AUTH_TOKEN_COOKIE } from "../../src/lib/auth/auth-session";
import { webBaseUrl } from "../support/config";
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

// Story 23-3: language switcher & NEXT_LOCALE persistence.
// The switcher lives in the dashboard header (protected routes), so these
// specs plant a sentinel auth cookie — the proxy guard only checks presence,
// and the shell makes no API calls without the user cookie.
async function signInForShell(context: import("@playwright/test").BrowserContext) {
  await context.addCookies([{ name: AUTH_TOKEN_COOKIE, value: "e2e-shell-token", url: webBaseUrl() }]);
}

test.describe("language switcher & persistence", () => {
  const switcherAria = {
    id: "Bahasa: Bahasa Indonesia. Buka menu bahasa",
    en: "Language: English. Open language menu",
  };

  test("switching to English re-renders the shell client-side and persists the cookie", async ({ page }) => {
    await signInForShell(page.context());
    // Multi-value query + hash must survive the locale switch verbatim.
    await page.goto("/id/settings?tab=preferences&tag=x&tag=y#shell");
    await expect(page.locator("html")).toHaveAttribute("lang", "id");

    const switcher = page.getByRole("button", { name: switcherAria.id });
    await expect(switcher).toBeVisible();

    // A reload wipes window globals; surviving proves the locale swap was
    // soft (client-side) navigation.
    await page.evaluate(() => {
      (globalThis as Record<string, unknown>).__e2eNoReload = true;
    });

    await switcher.click();
    await page.getByRole("menuitemradio", { name: "English" }).click();

    // Same path + full query + hash, English locale, no full reload (AC1).
    await expect(page).toHaveURL(/\/en\/settings\?tab=preferences&tag=x&tag=y#shell/);
    await expect(page.locator("html")).toHaveAttribute("lang", "en");
    await expect(page.getByRole("button", { name: switcherAria.en })).toBeVisible();
    expect(await page.evaluate(() => (globalThis as Record<string, unknown>).__e2eNoReload ?? null)).toBe(true);

    // Long-lived NEXT_LOCALE cookie names the new locale (AC2 precondition).
    const cookie = (await page.context().cookies()).find((c) => c.name === "NEXT_LOCALE");
    expect(cookie?.value).toBe("en");
    expect(cookie?.expires ?? -1).toBeGreaterThan(Date.now() / 1000 + 300 * 86400);

    // A fresh server navigation to / resolves to /en from the cookie (AC2).
    await page.goto("/");
    await expect(page).toHaveURL(/^https?:\/\/[^/]+\/en(\/|\?|#|$)/);
  });

  test("a hard visit to a page of the other locale never rewrites the NEXT_LOCALE choice", async ({
    page,
  }) => {
    // Review pass 1: next-intl's middleware syncCookie would Set-Cookie
    // NEXT_LOCALE=<url-locale> (session scope) on every prefixed document
    // request — routing.ts disables it, and this pins that off.
    await signInForShell(page.context());
    await page.goto("/id/settings?tab=preferences");
    await page.getByRole("button", { name: switcherAria.id }).click();
    await page.getByRole("menuitemradio", { name: "English" }).click();
    await expect(page).toHaveURL(/\/en\/settings\?tab=preferences/);

    // Hard document navigation to an explicit /id page (URL wins over cookie).
    await page.goto("/id/settings");
    await expect(page).toHaveURL(/^https?:\/\/[^/]+\/id\/settings/);
    await expect(page.locator("html")).toHaveAttribute("lang", "id");

    // The persisted choice survives: still en, still long-lived (not a
    // session-cookie downgrade).
    const cookie = (await page.context().cookies()).find((c) => c.name === "NEXT_LOCALE");
    expect(cookie?.value).toBe("en");
    expect(cookie?.expires ?? -1).toBeGreaterThan(Date.now() / 1000 + 300 * 86400);

    // A new tab in the same profile returns to /en (edge-matrix row 2).
    const tab = await page.context().newPage();
    await tab.goto("/");
    await expect(tab).toHaveURL(/^https?:\/\/[^/]+\/en(\/|\?|#|$)/);
  });

  test("/ with a NEXT_LOCALE cookie lands on that locale even without the sentinel token", async ({
    context,
    page,
  }) => {
    await context.addCookies([{ name: "NEXT_LOCALE", value: "en", url: webBaseUrl() }]);
    await page.goto("/");
    await expect(page).toHaveURL(/^https?:\/\/[^/]+\/en(\/|\?|#|$)/);
  });

  test("/ with an unsupported NEXT_LOCALE redirects the root to exactly /id, never 5xx", async ({
    context,
  }) => {
    await context.addCookies([{ name: "NEXT_LOCALE", value: "xx", url: webBaseUrl() }]);
    const response = await context.request.get("/", { maxRedirects: 0 });
    // The cookie is ignored (fall-through to intl), which 307s to the default.
    expect(response.status()).toBe(307);
    const location = response.headers()["location"];
    expect(location).toBeDefined();
    expect(new URL(location, webBaseUrl()).pathname).toBe("/id");
  });

  test("explicit prefixed URLs win over the NEXT_LOCALE cookie", async ({ context, page }) => {
    await context.addCookies([
      { name: "NEXT_LOCALE", value: "en", url: webBaseUrl() },
      { name: AUTH_TOKEN_COOKIE, value: "e2e-shell-token", url: webBaseUrl() },
    ]);
    await page.goto("/id/settings");
    await expect(page).toHaveURL(/^https?:\/\/[^/]+\/id\/settings$/);
    await expect(page.locator("html")).toHaveAttribute("lang", "id");
    // No /id -> /en rewriting, and the cookie keeps its chosen value.
    const cookie = (await context.cookies()).find((c) => c.name === "NEXT_LOCALE");
    expect(cookie?.value).toBe("en");
  });

  test("auth pages have no language switcher", async ({ page }) => {
    await page.goto("/en/auth/v2/login");
    await expect(page.getByRole("button", { name: switcherAria.en })).toHaveCount(0);
  });
});
