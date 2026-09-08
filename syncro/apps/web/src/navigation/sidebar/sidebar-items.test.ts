import { describe, expect, it } from "vitest";

import enMessages from "@/messages/en.json";
import idMessages from "@/messages/id.json";
import { sidebarItems } from "@/navigation/sidebar/sidebar-items";

// AC1 proof, data-layer: every sidebar titleKey/labelKey must resolve in BOTH
// catalogs. The e2e specs run unauthenticated (login shell has no sidebar), so
// rendered-label coverage lives here — a typo'd or deleted key fails this test
// instead of shipping raw keys to users.
function lookup(catalog: unknown, dottedKey: string): string | undefined {
  const value = dottedKey
    .split(".")
    .reduce<unknown>(
      (acc, segment) => (acc && typeof acc === "object" ? (acc as Record<string, unknown>)[segment] : undefined),
      catalog,
    );
  return typeof value === "string" ? value : undefined;
}

describe("sidebar navigation catalogs", () => {
  const keys: string[] = [];
  for (const group of sidebarItems) {
    if (group.labelKey) keys.push(`navigation.${group.labelKey}`);
    for (const item of group.items) {
      keys.push(`navigation.${item.titleKey}`);
      for (const sub of item.subItems ?? []) keys.push(`navigation.${sub.titleKey}`);
    }
  }

  it("collects every nav key from sidebarItems", () => {
    expect(keys.length).toBeGreaterThan(20);
  });

  it.each(keys)("%s resolves in en and id catalogs", (key) => {
    expect(lookup(enMessages, key), `missing in en.json: ${key}`).toBeTruthy();
    expect(lookup(idMessages, key), `missing in id.json: ${key}`).toBeTruthy();
  });

  it("common.comingSoon exists in both catalogs", () => {
    expect(lookup(enMessages, "common.comingSoon")).toBeTruthy();
    expect(lookup(idMessages, "common.comingSoon")).toBeTruthy();
  });
});
