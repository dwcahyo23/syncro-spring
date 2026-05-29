import type { Page } from "@playwright/test";

import { expect, test } from "../support/fixtures";

type Role = "SUPER_ADMIN" | "MANAGE" | "VIEWER";

type TaxonomyDimension = "CATEGORY" | "BRAND" | "KIND" | "TYPE";

type SparepartTaxonomyItem = {
  id: string;
  dimension: TaxonomyDimension;
  code: string;
  name: string;
  createdAt: string;
  updatedAt: string;
};

type SparepartRef = {
  id: string;
  code: string;
  name: string;
};

type SparepartItem = {
  id: string;
  code: string;
  name: string;
  category: SparepartRef;
  brand: SparepartRef;
  kind: SparepartRef;
  type: SparepartRef;
  createdAt: string;
  updatedAt: string;
};

const routePath = "/dashboard/master-data/spareparts";
const timestamp = "2026-05-28T00:00:00Z";

const taxonomyItems: SparepartTaxonomyItem[] = [
  taxonomyItem("tax-category-bearing", "CATEGORY", "CAT-BRG", "Bearing"),
  taxonomyItem("tax-category-belt", "CATEGORY", "CAT-BLT", "Belt"),
  taxonomyItem("tax-brand-wecon", "BRAND", "BRD-WEC", "Wecon"),
  taxonomyItem("tax-brand-skf", "BRAND", "BRD-SKF", "SKF"),
  taxonomyItem("tax-kind-plc", "KIND", "KND-PLC", "PLC"),
  taxonomyItem("tax-kind-bearing", "KIND", "KND-BRG", "Bearing Unit"),
  taxonomyItem("tax-type-electric", "TYPE", "TYP-ELC", "Electric"),
  taxonomyItem("tax-type-mechanical", "TYPE", "TYP-MEC", "Mechanical"),
];

const spareparts: SparepartItem[] = [
  {
    id: "sparepart-bf-08410",
    code: "BF-08410",
    name: "Electric PLC Wecon LX5",
    category: { id: "tax-category-bearing", code: "CAT-BRG", name: "Bearing" },
    brand: { id: "tax-brand-wecon", code: "BRD-WEC", name: "Wecon" },
    kind: { id: "tax-kind-plc", code: "KND-PLC", name: "PLC" },
    type: { id: "tax-type-electric", code: "TYP-ELC", name: "Electric" },
    createdAt: timestamp,
    updatedAt: timestamp,
  },
  {
    id: "sparepart-jbf19",
    code: "JBF19",
    name: "JBF19 Bearing Housing",
    category: { id: "tax-category-belt", code: "CAT-BLT", name: "Belt" },
    brand: { id: "tax-brand-skf", code: "BRD-SKF", name: "SKF" },
    kind: { id: "tax-kind-bearing", code: "KND-BRG", name: "Bearing Unit" },
    type: { id: "tax-type-mechanical", code: "TYP-MEC", name: "Mechanical" },
    createdAt: timestamp,
    updatedAt: timestamp,
  },
];

test.describe("ATDD RED Story 2.5 sparepart management", () => {
  test.beforeEach(async ({ page }) => {
    await setAuthUser(page, "MANAGE");
  });

  test.skip("2.5-E2E-001 P0 creates sparepart with CATEGORY BRAND KIND TYPE selectors", async ({ page }) => {
    await mockTaxonomy(page);
    await mockSparepartList(page, []);
    let createPayload: Record<string, unknown> | null = null;
    await page.route("**/api/v1/spareparts", async (route) => {
      if (route.request().method() !== "POST") {
        await route.fallback();
        return;
      }
      createPayload = route.request().postDataJSON();
      await route.fulfill(jsonResponse(spareparts[0], 201));
    });

    await page.goto(routePath);
    await page.getByRole("button", { name: "Create sparepart" }).click();
    await expect(page.getByRole("dialog", { name: "Create sparepart" })).toBeVisible();
    await page.getByLabel("Code").fill("BF-08410");
    await page.getByLabel("Name").fill("Electric PLC Wecon LX5");
    await selectByLabel(page, "Category", "Bearing (CAT-BRG)");
    await selectByLabel(page, "Brand", "Wecon (BRD-WEC)");
    await selectByLabel(page, "Kind", "PLC (KND-PLC)");
    await selectByLabel(page, "Type", "Electric (TYP-ELC)");
    await page.getByRole("button", { name: "Save sparepart" }).click();

    await expect
      .poll(() => createPayload)
      .toMatchObject({
        code: "BF-08410",
        name: "Electric PLC Wecon LX5",
        categoryId: "tax-category-bearing",
        brandId: "tax-brand-wecon",
        kindId: "tax-kind-plc",
        typeId: "tax-type-electric",
      });
    await expect(page.getByRole("cell", { name: "BF-08410" })).toBeVisible();
  });

  test.skip("2.5-E2E-002 P0 lists searches and filters spareparts by taxonomy", async ({ page }) => {
    const requests: string[] = [];
    await mockTaxonomy(page);
    await page.route("**/api/v1/spareparts**", async (route) => {
      requests.push(route.request().url());
      await route.fulfill(jsonResponse({ items: spareparts, totalElements: spareparts.length }));
    });

    await page.goto(routePath);

    await expect(page.getByPlaceholder("Search code or name")).toBeVisible();
    await expect(page.getByRole("cell", { name: "BF-08410" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "Electric PLC Wecon LX5" })).toBeVisible();
    await page.getByPlaceholder("Search code or name").fill("JBF19");
    await expect.poll(() => requests.some((url) => url.includes("search=JBF19"))).toBe(true);
    await page.getByRole("combobox").filter({ hasText: "All category" }).click();
    await page.getByRole("option", { name: "Bearing" }).click();
    await expect.poll(() => requests.some((url) => url.includes("categoryId=tax-category-bearing"))).toBe(true);
    await page.getByRole("combobox").filter({ hasText: "All brand" }).click();
    await page.getByRole("option", { name: "Wecon" }).click();
    await expect.poll(() => requests.some((url) => url.includes("brandId=tax-brand-wecon"))).toBe(true);
    await page.getByRole("combobox").filter({ hasText: "All kind" }).click();
    await page.getByRole("option", { name: "PLC" }).click();
    await expect.poll(() => requests.some((url) => url.includes("kindId=tax-kind-plc"))).toBe(true);
    await page.getByRole("combobox").filter({ hasText: "All type" }).click();
    await page.getByRole("option", { name: "Electric" }).click();
    await expect.poll(() => requests.some((url) => url.includes("typeId=tax-type-electric"))).toBe(true);
  });

  test.skip("2.5-E2E-003 P0 edits sparepart taxonomy and bounded fields", async ({ page }) => {
    let updatePayload: Record<string, unknown> | null = null;
    await mockTaxonomy(page);
    await mockSparepartList(page, spareparts);
    await page.route("**/api/v1/spareparts/sparepart-bf-08410", async (route) => {
      if (route.request().method() !== "PUT") {
        await route.fallback();
        return;
      }
      updatePayload = route.request().postDataJSON();
      await route.fulfill(jsonResponse({ ...spareparts[0], name: "Electric PLC Wecon LX5 Updated" }));
    });

    await page.goto(routePath);
    await page
      .getByRole("row", { name: /BF-08410/ })
      .getByRole("button", { name: "Edit" })
      .click();
    await expect(page.getByRole("dialog", { name: "Edit sparepart" })).toBeVisible();
    await page.getByLabel("Name").fill("Electric PLC Wecon LX5 Updated");
    await selectByLabel(page, "Brand", "SKF (BRD-SKF)");
    await page.getByRole("button", { name: "Save sparepart" }).click();

    await expect
      .poll(() => updatePayload)
      .toMatchObject({
        code: "BF-08410",
        name: "Electric PLC Wecon LX5 Updated",
        brandId: "tax-brand-skf",
      });
  });

  test.skip("2.5-E2E-004 P0 deletes sparepart without dependent installations", async ({ page }) => {
    let deleteCalled = false;
    await mockTaxonomy(page);
    await mockSparepartList(page, spareparts);
    await page.route("**/api/v1/spareparts/sparepart-bf-08410", async (route) => {
      if (route.request().method() !== "DELETE") {
        await route.fallback();
        return;
      }
      deleteCalled = true;
      await route.fulfill({ status: 204, body: "" });
    });

    await page.goto(routePath);
    await page
      .getByRole("row", { name: /BF-08410/ })
      .getByRole("button", { name: "Delete" })
      .click();
    await expect(page.getByRole("alertdialog", { name: "Delete sparepart?" })).toBeVisible();
    await page.getByRole("button", { name: "Delete sparepart" }).click();

    await expect.poll(() => deleteCalled).toBe(true);
  });

  test.skip("2.5-E2E-005 P1 shows VIEWER read-only state and no mutation actions", async ({ page }) => {
    await setAuthUser(page, "VIEWER");
    await mockTaxonomy(page);
    await mockSparepartList(page, spareparts);

    await page.goto(routePath);

    await expect(page.locator("main").getByText("Read-only").first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Create sparepart" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Edit" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Delete" })).toHaveCount(0);
    await expect(page.getByRole("row", { name: /BF-08410/ }).getByText("View only")).toBeVisible();
  });

  test.skip("2.5-E2E-006 P1 shows empty error and taxonomy-missing durable states", async ({ page }) => {
    await page.route("**/api/v1/sparepart-taxonomies**", async (route) => {
      await route.fulfill(jsonResponse({ items: taxonomyItems.slice(0, 1), totalElements: 1 }));
    });
    await mockSparepartList(page, []);

    await page.goto(routePath);
    await expect(page.getByText("Taxonomy setup incomplete")).toBeVisible();
    await expect(page.getByRole("button", { name: "Create sparepart" })).toBeDisabled();

    await page.unroute("**/api/v1/sparepart-taxonomies**");
    await page.unroute("**/api/v1/spareparts**");
    await mockTaxonomy(page);
    await mockSparepartList(page, []);
    await page.reload();
    await expect(page.getByText("No spareparts yet")).toBeVisible();

    await page.unroute("**/api/v1/spareparts**");
    await page.route("**/api/v1/spareparts**", async (route) => {
      await route.fulfill(jsonResponse({ code: "SPAREPART_LIST_FAILED", message: "Sparepart list unavailable" }, 500));
    });
    await page.reload();
    await expect(page.getByText("Spareparts could not be loaded")).toBeVisible();
    await expect(page.getByRole("button", { name: "Retry spareparts" })).toBeVisible();
  });

  test.skip("2.5-E2E-007 P1 shows safe delete conflict message", async ({ page }) => {
    await mockTaxonomy(page);
    await mockSparepartList(page, spareparts);
    await page.route("**/api/v1/spareparts/sparepart-bf-08410", async (route) => {
      if (route.request().method() !== "DELETE") {
        await route.fallback();
        return;
      }
      await route.fulfill(
        jsonResponse(
          { code: "SPAREPART_IN_USE", message: "Deletion blocked because installed spareparts depend on BF-08410." },
          409,
        ),
      );
    });

    await page.goto(routePath);
    await page
      .getByRole("row", { name: /BF-08410/ })
      .getByRole("button", { name: "Delete" })
      .click();
    await page.getByRole("button", { name: "Delete sparepart" }).click();

    await expect(
      page
        .getByRole("alertdialog", { name: "Delete sparepart?" })
        .getByText("Deletion blocked because installed spareparts depend on BF-08410."),
    ).toBeVisible();
  });
});

function taxonomyItem(id: string, dimension: TaxonomyDimension, code: string, name: string): SparepartTaxonomyItem {
  return { id, dimension, code, name, createdAt: timestamp, updatedAt: timestamp };
}

async function setAuthUser(page: Page, role: Role) {
  const authUser = JSON.stringify({
    id: `user-${role.toLowerCase()}`,
    loginIdentifier: `${role.toLowerCase()}@syncro.local`,
    applicationRole: role,
  });

  await page.context().addCookies(
    ["http://localhost:3000", "http://localhost:3001", "http://localhost:3002"].flatMap((url) => [
      { name: "syncro_auth_token", value: `token-${role.toLowerCase()}`, url },
      { name: "syncro_auth_user", value: encodeURIComponent(authUser), url },
    ]),
  );
}

async function mockTaxonomy(page: Page, items = taxonomyItems) {
  await page.route("**/api/v1/sparepart-taxonomies**", async (route) => {
    await route.fulfill(jsonResponse({ items, totalElements: items.length }));
  });
}

async function mockSparepartList(page: Page, items = spareparts) {
  await page.route("**/api/v1/spareparts**", async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill(jsonResponse({ items, totalElements: items.length }));
      return;
    }
    await route.fallback();
  });
}

async function selectByLabel(page: Page, label: string, optionName: string) {
  await page.locator("label").filter({ hasText: label }).locator("..").getByRole("combobox").click();
  await page.getByRole("option", { name: optionName }).click();
}

function jsonResponse(body: unknown, status = 200) {
  return { status, contentType: "application/json", body: JSON.stringify(body) };
}
