import { expect, test } from "../support/fixtures";

test.describe("dashboard shell", () => {
  test("given user opens root when app loads then public redirect or protected login is reachable", async ({
    page,
  }) => {
    await page.goto("/");

    await expect(page).toHaveURL(/\/(operations-overview|dashboard\/operations-overview|auth\/v2\/login)/);
    await expect(page.locator("body")).toBeVisible();
  });
});
