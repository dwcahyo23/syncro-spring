import { expect, test } from "../support/fixtures";

// Story 2.9 ATDD E2E RED-phase scaffold. Every case stays skipped: they need a running app
// (playwright.config webServer) plus an authenticated SUPER_ADMIN session and seeded audit
// entries, mirroring operator action #5 in the spec. Activate them in the GREEN phase once
// the page-reset fix and durable UI states are verified.
test.describe("Story 2.9 ATDD E2E RED-phase scaffold: /dashboard/audit-log", () => {
  // 23.1: routes live under the /id locale prefix; the unprefixed URL still
  // resolves via the locale redirect.
  test.skip("2.9-ATDD-E2E-001 P1 SUPER_ADMIN sees heading and a dense sortable desktop table", async ({ page }) => {
    await page.goto("/id/dashboard/audit-log");
    await expect(page.getByText("Audit Log", { exact: true })).toBeVisible();
    await expect(page.getByText(/Immutable history of master data changes/)).toBeVisible();
    await expect(page.getByRole("table")).toBeVisible();
    for (const header of ["Timestamp", "Actor", "Action", "Entity"]) {
      await expect(page.getByRole("button", { name: header })).toBeVisible();
    }
    await expect(page.getByRole("button", { name: "Toggle change detail" }).first()).toBeVisible();
  });

  test.skip("2.9-ATDD-E2E-002 P1 filtering by entity type refetches results and resets page to 0", async ({ page }) => {
    await page.goto("/id/dashboard/audit-log");
    await page.getByLabel("Entity type").click();
    await page.getByRole("option", { name: "Machine" }).click();
    await expect(page.getByText("Machine", { exact: true }).first()).toBeVisible();
    await expect(page.getByText("Page 1 of")).toBeVisible();
  });

  test.skip("2.9-ATDD-E2E-003 P1 empty list shows 'No audit entries yet'", async ({ page }) => {
    await page.goto("/id/dashboard/audit-log");
    await expect(page.getByText("No audit entries yet")).toBeVisible();
  });

  test.skip("2.9-ATDD-E2E-004 P1 filtered-empty shows 'No matching entries' and Reset filters restores the list", async ({
    page,
  }) => {
    await page.goto("/id/dashboard/audit-log");
    await page.getByLabel("Actor").fill("__no_such_actor__@syncro.dev");
    await expect(page.getByText("No matching entries")).toBeVisible();
    await page.getByRole("button", { name: "Reset filters" }).click();
    await expect(page.getByText("No audit entries yet")).toBeVisible();
  });

  test.describe("mobile viewport", () => {
    test.use({ viewport: { width: 375, height: 700 } });

    test.skip("2.9-ATDD-E2E-005 P1 mobile shows date-grouped stacked cards with expandable detail", async ({
      page,
    }) => {
      await page.goto("/id/dashboard/audit-log");
      const mobileCards = page.locator(".md\\:hidden").first();
      await expect(page.getByRole("table")).toBeHidden();
      await expect(mobileCards.locator("h3").first()).toBeVisible();
      const detailToggle = mobileCards.getByRole("button", { name: "Toggle change detail" }).first();
      await expect(detailToggle).toBeVisible();
      await detailToggle.click();
      await expect(mobileCards.getByText("Field", { exact: true })).toBeVisible();
      await expect(mobileCards.getByText("Before", { exact: true })).toBeVisible();
      await expect(mobileCards.getByText("After", { exact: true })).toBeVisible();
    });
  });
});
