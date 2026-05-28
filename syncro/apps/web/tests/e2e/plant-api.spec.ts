import { expect, test } from "../support/fixtures";

test.describe("plant API fixture", () => {
  test("given generated plant data when API boundary is mocked then fixture data remains stable", async ({
    page,
    testData,
  }) => {
    await page.route("**/api/v1/plants**", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ content: [testData.plant], totalElements: 1 }),
      });
    });

    await page.goto("/master-data/plants");

    expect(testData.plant.code).toMatch(/^PLT-[A-Z0-9]{6}$/);
    await expect(page.locator("body")).toBeVisible();
  });
});
