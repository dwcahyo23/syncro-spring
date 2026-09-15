import { describe, expect, it } from "vitest";

import { sidebarItems } from "@/navigation/sidebar/sidebar-items";

// spec "Template Laporan Cetak": frozen data-literal contract for the new
// Konfigurasi entry — pattern mirrors maintenance-nav-contract.test.ts. If a
// future edit renames the URL, flips the roles, or drops the locale-gated
// rewrite, these tests fail and force an explicit decision.

interface RouteEntry {
  source: string;
  destination: string;
  permanent?: boolean;
}
type NextConfigWithRoutes = {
  rewrites?: () => RouteEntry[] | Promise<RouteEntry[]>;
};

async function loadRewrites(): Promise<RouteEntry[]> {
  const mod = (await import("../../../next.config.mjs")) as { default: unknown };
  const config = mod.default as NextConfigWithRoutes;
  if (!config.rewrites) throw new Error("next.config lost rewrites(); update this test");
  return await config.rewrites();
}

describe("sidebar Konfigurasi — Template Laporan entry", () => {
  const group = sidebarItems.find((g) => g.labelKey === "groups.configuration");

  it("exists with the public URL, i18n key, and the two maintenance-role admins", () => {
    const entry = group?.items.find((i) => i.titleKey === "items.reportTemplates");
    expect(entry, "items.reportTemplates entry in groups.configuration").toBeDefined();
    expect(entry?.url).toBe("/report-templates");
    // Frozen: only SUPER_ADMIN & MANAGER_MAINTENANCE (matrix "Staf biasa" — STAFF must not see the menu).
    expect(entry?.roles).toEqual(["SUPER_ADMIN", "MANAGER_MAINTENANCE"]);
  });

  it("sits in Konfigurasi, not in any operational/maintenance group", () => {
    for (const g of sidebarItems) {
      if (g.labelKey === "groups.configuration") continue;
      expect(
        g.items.some((i) => i.url === "/report-templates"),
        `dup in ${g.labelKey}`,
      ).toBe(false);
    }
  });
});

describe("public URL rewrite contract /report-templates", () => {
  it("rewrites the locale-gated public path to the internal dashboard file location", async () => {
    const list = await loadRewrites();
    const entry = list.find((r) => r.source === "/:locale(id|en)/report-templates");
    expect(entry, "rewrite source /:locale(id|en)/report-templates").toBeDefined();
    expect(entry?.destination).toBe("/:locale/dashboard/report-templates");
    // `:locale(id|en)` gate: unknown locales like /fr can never be rewritten.
    expect(entry?.source).toContain("id|en");
  });
});
