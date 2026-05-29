import type { Page } from "@playwright/test";

import type { InstallationView, MachineView, SparepartView } from "@/lib/api/generated/model";

import { expect, test } from "../support/fixtures";

type Role = "SUPER_ADMIN" | "MANAGE" | "VIEWER" | "STAFF";

type InstallationItem = InstallationView;
type MachineOption = MachineView;
type SparepartOption = SparepartView;

const routePath = "/dashboard/master-data/installations";
const timestamp = "2026-05-28T00:00:00Z";

const machines: MachineOption[] = [
  {
    id: "machine-pack-01",
    code: "PKG-01",
    name: "Packaging Line 01",
    plantId: "plant-a",
    plantCode: "PLT-A",
    plantName: "Plant A",
    machineGroupId: "group-pack",
    machineGroupName: "Packaging",
    status: "ACTIVE",
  },
];

const spareparts: SparepartOption[] = [
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
];

const installations: InstallationItem[] = [
  {
    id: "installation-pkg-01-plc",
    machineId: "machine-pack-01",
    machineCode: "PKG-01",
    machineName: "Packaging Line 01",
    plantId: "plant-a",
    plantCode: "PLT-A",
    plantName: "Plant A",
    machineGroupId: "group-pack",
    machineGroupName: "Packaging",
    sparepartId: "sparepart-bf-08410",
    sparepartCode: "BF-08410",
    sparepartName: "Electric PLC Wecon LX5",
    category: spareparts[0].category,
    brand: spareparts[0].brand,
    kind: spareparts[0].kind,
    type: spareparts[0].type,
    expectedProductionCount: 1_000_000,
    baselineCounter: 42_000,
    thresholdPercentage: 90,
    currentCount: null,
    consumedProductionCount: null,
    consumedPercentage: null,
    calculationBasis: "COUNTER_BASED",
    installedAt: timestamp,
    createdAt: timestamp,
    updatedAt: timestamp,
  },
];

test.describe("ATDD RED Story 2.6 machine sparepart installations", () => {
  test.beforeEach(async ({ page }) => {
    await setAuthUser(page, "MANAGE");
  });

  test.skip("2.6-E2E-001 P0 creates installation with shadcn machine and sparepart selectors", async ({ page }) => {
    let createPayload: Record<string, unknown> | null = null;
    let listItems: InstallationItem[] = [];
    await mockReferenceData(page);
    await mockStatefulInstallationList(page, () => listItems);
    await page.route("**/api/v1/machine-sparepart-installations", async (route) => {
      if (route.request().method() !== "POST") {
        await route.fallback();
        return;
      }
      createPayload = route.request().postDataJSON();
      listItems = installations;
      await route.fulfill(jsonResponse(installations[0], 201));
    });

    await page.goto(routePath, { waitUntil: "domcontentloaded" });
    await page.getByRole("button", { name: "Create installation" }).click();
    await expect(page.getByRole("dialog", { name: "Create installation" })).toBeVisible();
    await selectByLabel(page, "Machine", "PKG-01 - Packaging Line 01");
    await selectByLabel(page, "Sparepart", "BF-08410 - Electric PLC Wecon LX5");
    await page.getByLabel("Expected production count").fill("1000000");
    await page.getByLabel("Baseline counter").fill("42000");
    await page.getByLabel("Threshold percentage").fill("75");
    await page.getByRole("button", { name: "Save installation" }).click();

    await expect
      .poll(() => createPayload)
      .toMatchObject({
        machineId: "machine-pack-01",
        sparepartId: "sparepart-bf-08410",
        expectedProductionCount: 1_000_000,
        baselineCounter: 42_000,
        thresholdPercentage: 75,
      });
    await expect(page.getByRole("cell", { name: "PKG-01" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "BF-08410" })).toBeVisible();
  });

  test.skip("2.6-E2E-002 P0 lists baseline current expected threshold and counter-based evidence without fabricated current count", async ({
    page,
  }) => {
    await mockReferenceData(page);
    await mockInstallationList(page, installations);

    await page.goto(routePath, { waitUntil: "domcontentloaded" });

    await expect(page.getByRole("cell", { name: "Plant A" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "Packaging" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "PKG-01" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "BF-08410" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "1,000,000" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "42,000" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "90%" })).toBeVisible();
    await expect(page.getByText("Current count unavailable")).toBeVisible();
    await expect(page.getByText("Counter-based")).toBeVisible();
  });

  test.skip("2.6-E2E-003 P0 edits lifetime values and keeps machine sparepart links stable", async ({ page }) => {
    let listItems = [...installations];
    let updatePayload: Record<string, unknown> | null = null;
    await mockReferenceData(page);
    await mockStatefulInstallationList(page, () => listItems);
    await page.route("**/api/v1/machine-sparepart-installations/installation-pkg-01-plc", async (route) => {
      if (route.request().method() !== "PUT") {
        await route.fallback();
        return;
      }
      updatePayload = route.request().postDataJSON();
      listItems = [
        { ...installations[0], expectedProductionCount: 2_000_000, baselineCounter: 50_000, thresholdPercentage: 80 },
      ];
      await route.fulfill(jsonResponse(listItems[0]));
    });

    await page.goto(routePath, { waitUntil: "domcontentloaded" });
    await page
      .getByRole("row", { name: /PKG-01/ })
      .getByRole("button", { name: "Edit" })
      .click();
    await page.getByLabel("Expected production count").fill("2000000");
    await page.getByLabel("Baseline counter").fill("50000");
    await page.getByLabel("Threshold percentage").fill("80");
    await page.getByRole("button", { name: "Save installation" }).click();

    await expect
      .poll(() => updatePayload)
      .toMatchObject({
        expectedProductionCount: 2_000_000,
        baselineCounter: 50_000,
        thresholdPercentage: 80,
      });
    await expect(page.getByRole("cell", { name: "2,000,000" })).toBeVisible();
  });

  test.skip("2.6-E2E-004 P0 deletes installation with confirmation", async ({ page }) => {
    let listItems = [...installations];
    await mockReferenceData(page);
    await mockStatefulInstallationList(page, () => listItems);
    await page.route("**/api/v1/machine-sparepart-installations/installation-pkg-01-plc", async (route) => {
      if (route.request().method() !== "DELETE") {
        await route.fallback();
        return;
      }
      listItems = [];
      await route.fulfill({ status: 204, body: "" });
    });

    await page.goto(routePath, { waitUntil: "domcontentloaded" });
    await page
      .getByRole("row", { name: /PKG-01/ })
      .getByRole("button", { name: "Delete" })
      .click();
    await expect(page.getByRole("alertdialog", { name: "Delete installation?" })).toBeVisible();
    await page.getByRole("button", { name: "Delete installation" }).click();

    await expect(page.getByText("Installation deleted.")).toBeVisible();
    await expect(page.getByRole("row", { name: /PKG-01/ })).toHaveCount(0);
  });

  test.skip("2.6-E2E-005 P1 shows VIEWER read-only state and no mutation actions", async ({ page }) => {
    await setAuthUser(page, "VIEWER");
    await mockReferenceData(page);
    await mockInstallationList(page, installations);

    await page.goto(routePath, { waitUntil: "domcontentloaded" });

    await expect(page.locator("main").getByText("Read-only").first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Create installation" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Edit" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Delete" })).toHaveCount(0);
    await expect(page.getByRole("row", { name: /PKG-01/ }).getByText("View only")).toBeVisible();
  });

  test.skip("2.6-E2E-006 P1 shows forbidden loading empty setup prompt and API error durable states", async ({
    page,
  }) => {
    await setAuthUser(page, "STAFF");
    await page.goto(routePath, { waitUntil: "domcontentloaded" });
    await expect(page.getByText("Permission denied")).toBeVisible();

    await setAuthUser(page, "MANAGE");
    await mockMachines(page, []);
    await mockSpareparts(page, spareparts);
    await mockInstallationList(page, []);
    await page.goto(routePath, { waitUntil: "domcontentloaded" });
    await expect(page.getByText("No machines available for installation setup")).toBeVisible();

    await page.unroute("**/api/v1/machine-sparepart-installations**");
    await mockReferenceData(page);
    await page.route("**/api/v1/machine-sparepart-installations**", async (route) => {
      await route.fulfill(
        jsonResponse({ code: "INSTALLATION_LIST_FAILED", message: "Installations unavailable" }, 500),
      );
    });
    await page.reload();
    await expect(page.getByText("Installations could not be loaded")).toBeVisible();
    await expect(page.getByRole("button", { name: "Retry installations" })).toBeVisible();
  });

  test.skip("2.6-E2E-007 P1 shows safe delete conflict message", async ({ page }) => {
    await mockReferenceData(page);
    await mockInstallationList(page, installations);
    await page.route("**/api/v1/machine-sparepart-installations/installation-pkg-01-plc", async (route) => {
      if (route.request().method() === "DELETE") {
        await route.fulfill(
          jsonResponse(
            {
              code: "INSTALLATION_IN_USE",
              message: "Deletion blocked because dependent lifetime evidence references this installation.",
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
      .getByRole("row", { name: /PKG-01/ })
      .getByRole("button", { name: "Delete" })
      .click();
    await page.getByRole("button", { name: "Delete installation" }).click();

    await expect(
      page
        .getByRole("alertdialog", { name: "Delete installation?" })
        .getByText("Deletion blocked because dependent lifetime evidence references this installation."),
    ).toBeVisible();
  });
});

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

async function mockReferenceData(page: Page) {
  await mockMachines(page, machines);
  await mockSpareparts(page, spareparts);
}

async function mockMachines(page: Page, items = machines) {
  await page.route("**/api/v1/machines**", async (route) => {
    await route.fulfill(jsonResponse({ items, totalElements: items.length }));
  });
}

async function mockSpareparts(page: Page, items = spareparts) {
  await page.route("**/api/v1/spareparts**", async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill(jsonResponse({ items, totalElements: items.length }));
      return;
    }
    await route.fallback();
  });
}

async function mockInstallationList(page: Page, items = installations) {
  await mockStatefulInstallationList(page, () => items);
}

async function mockStatefulInstallationList(page: Page, items: () => InstallationItem[]) {
  await page.route("**/api/v1/machine-sparepart-installations**", async (route) => {
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
  return { status, contentType: "application/json", body: JSON.stringify(body) };
}
