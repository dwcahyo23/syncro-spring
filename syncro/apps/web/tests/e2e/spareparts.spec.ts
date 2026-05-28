import type { Page } from "@playwright/test";

import type { SparepartTaxonomyView, SparepartView } from "@/lib/api/generated/model";

import { expect, test } from "../support/fixtures";

type Role = "SUPER_ADMIN" | "MANAGE" | "VIEWER" | "STAFF";

type SparepartTaxonomyItem = Required<SparepartTaxonomyView>;
type SparepartItem = Required<SparepartView>;

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

test.describe("sparepart management", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeEach(async ({ page }) => {
    await setAuthUser(page, "MANAGE");
  });

  test("[P0] shows table and refetches when search or taxonomy filters change", async ({ page }) => {
    const requests: string[] = [];
    await mockTaxonomy(page);
    await page.route("**/api/v1/spareparts**", async (route) => {
      requests.push(route.request().url());
      await route.fulfill(jsonResponse({ items: spareparts, totalElements: spareparts.length }));
    });

    await page.goto(routePath, { waitUntil: "domcontentloaded" });

    await expect(page.getByText("Manage global sparepart master data")).toBeVisible();
    await expect(page.getByRole("cell", { name: "BF-08410" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "Electric PLC Wecon LX5" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "Bearing" }).first()).toBeVisible();

    await page.getByPlaceholder("Search code or name").fill("BF-08410");
    await expect.poll(() => requests.some((url) => url.includes("search=BF-08410"))).toBe(true);

    await page.getByRole("combobox").filter({ hasText: "All brand" }).click();
    await page.getByRole("option", { name: "Wecon" }).click();
    await expect.poll(() => requests.some((url) => url.includes("brandId=tax-brand-wecon"))).toBe(true);
  });

  test("[P0] creates sparepart with taxonomy selectors", async ({ page }) => {
    await mockTaxonomy(page);
    let listItems: SparepartItem[] = [];
    await mockStatefulSparepartList(page, () => listItems);
    let createPayload: Record<string, unknown> | null = null;
    await page.route("**/api/v1/spareparts", async (route) => {
      if (route.request().method() !== "POST") {
        await route.fallback();
        return;
      }
      createPayload = route.request().postDataJSON();
      listItems = [spareparts[0]];
      await route.fulfill(jsonResponse({ ...spareparts[0], code: "BF-08410", name: "Electric PLC Wecon LX5" }, 201));
    });

    await page.goto(routePath, { waitUntil: "domcontentloaded" });
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
    await expect(page.getByText("Sparepart created.")).toBeVisible();
    await expect(page.getByRole("cell", { name: "BF-08410" })).toBeVisible();
    await expect(page.getByText("No spareparts yet")).toHaveCount(0);
  });

  test("[P0] edits and deletes sparepart successfully", async ({ page }) => {
    await mockTaxonomy(page);
    let listItems = [...spareparts];
    await mockStatefulSparepartList(page, () => listItems);
    await page.route("**/api/v1/spareparts/sparepart-bf-08410", async (route) => {
      if (route.request().method() === "PUT") {
        listItems = [{ ...spareparts[0], name: "Electric PLC Wecon LX5 Updated" }, spareparts[1]];
        await route.fulfill(jsonResponse({ ...spareparts[0], name: "Electric PLC Wecon LX5 Updated" }));
        return;
      }
      if (route.request().method() === "DELETE") {
        listItems = [spareparts[1]];
        await route.fulfill({ status: 204, body: "" });
        return;
      }
      await route.fallback();
    });

    await page.goto(routePath, { waitUntil: "domcontentloaded" });
    await page
      .getByRole("row", { name: /BF-08410/ })
      .getByRole("button", { name: "Edit" })
      .click();
    await page.getByLabel("Name").fill("Electric PLC Wecon LX5 Updated");
    await page.getByRole("button", { name: "Save sparepart" }).click();
    await expect(page.getByText("Sparepart updated.")).toBeVisible();
    await expect(page.getByRole("cell", { name: "Electric PLC Wecon LX5 Updated" })).toBeVisible();

    await page
      .getByRole("row", { name: /BF-08410/ })
      .getByRole("button", { name: "Delete" })
      .click();
    await expect(page.getByRole("alertdialog", { name: "Delete sparepart?" })).toBeVisible();
    await page.getByRole("button", { name: "Delete sparepart" }).click();
    await expect(page.getByText("Sparepart deleted.")).toBeVisible();
    await expect(page.getByRole("row", { name: /BF-08410/ })).toHaveCount(0);
  });

  test("[P1] renders forbidden state for disallowed role", async ({ page }) => {
    await setAuthUser(page, "STAFF");

    await page.goto(routePath, { waitUntil: "domcontentloaded" });

    await expect(page.getByText("Permission denied")).toBeVisible();
    await expect(page.getByText("You don't have permission to access this page.")).toBeVisible();
  });

  test("[P1] renders viewer read-only state without mutation actions", async ({ page }) => {
    await setAuthUser(page, "VIEWER");
    await mockTaxonomy(page);
    await mockSparepartList(page, spareparts);

    await page.goto(routePath, { waitUntil: "domcontentloaded" });

    await expect(page.locator("main").getByText("Read-only").first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Create sparepart" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Edit" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Delete" })).toHaveCount(0);
    await expect(page.getByRole("row", { name: /BF-08410/ }).getByText("View only")).toBeVisible();
  });

  test("[P1] shows empty API error and taxonomy-missing states", async ({ page }) => {
    await page.route("**/api/v1/sparepart-taxonomies**", async (route) => {
      await route.fulfill(jsonResponse({ items: taxonomyItems.slice(0, 1), totalElements: 1 }));
    });
    await mockSparepartList(page, []);

    await page.goto(routePath, { waitUntil: "domcontentloaded" });
    await expect(page.getByText("Taxonomy setup incomplete")).toBeVisible();
    await expect(page.getByRole("button", { name: "Create sparepart" })).toBeDisabled();

    await page.unroute("**/api/v1/sparepart-taxonomies**");
    await page.unroute("**/api/v1/spareparts**");
    await mockTaxonomy(page);
    await mockSparepartList(page, []);
    await page.reload();
    await expect(page.getByText("No spareparts yet")).toBeVisible();

    await page.unroute("**/api/v1/sparepart-taxonomies**");
    await page.unroute("**/api/v1/spareparts**");
    await mockTaxonomy(page);
    await page.route("**/api/v1/spareparts**", async (route) => {
      await route.fulfill(jsonResponse({ code: "SPAREPART_LIST_FAILED", message: "Sparepart list unavailable" }, 500));
    });
    await page.reload();
    await expect(page.getByText("Spareparts could not be loaded")).toBeVisible();
    await expect(page.getByRole("button", { name: "Retry spareparts" })).toBeVisible();
  });

  test("[P1] shows delete conflict message when installed spareparts depend on target", async ({ page }) => {
    await mockTaxonomy(page);
    await mockSparepartList(page, spareparts);
    await page.route("**/api/v1/spareparts/sparepart-bf-08410", async (route) => {
      if (route.request().method() === "DELETE") {
        await route.fulfill(
          jsonResponse(
            {
              code: "SPAREPART_IN_USE",
              message: "Deletion blocked because installed spareparts depend on BF-08410.",
            },
            409,
          ),
        );
        return;
      }
      await route.fallback();
    });

    await page.goto(routePath, { waitUntil: "domcontentloaded" });
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

function taxonomyItem(
  id: string,
  dimension: SparepartTaxonomyItem["dimension"],
  code: string,
  name: string,
): SparepartTaxonomyItem {
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
      {
        name: "syncro_auth_token",
        value: `token-${role.toLowerCase()}`,
        url,
      },
      {
        name: "syncro_auth_user",
        value: authUser,
        url,
      },
    ]),
  );
}

async function mockTaxonomy(page: Page, items = taxonomyItems) {
  await page.route("**/api/v1/sparepart-taxonomies**", async (route) => {
    await route.fulfill(jsonResponse({ items, totalElements: items.length }));
  });
}

async function mockSparepartList(page: Page, items = spareparts) {
  await mockStatefulSparepartList(page, () => items);
}

async function mockStatefulSparepartList(page: Page, items: () => SparepartItem[]) {
  await page.route("**/api/v1/spareparts**", async (route) => {
    if (route.request().method() === "GET") {
      const currentItems = items();
      await route.fulfill(jsonResponse({ items: currentItems, totalElements: currentItems.length }));
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
  return {
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  };
}
