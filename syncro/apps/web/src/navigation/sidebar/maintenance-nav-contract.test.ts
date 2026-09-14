import { describe, expect, it } from "vitest";

import { sidebarItems } from "@/navigation/sidebar/sidebar-items";

import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";

// Frozen I/O matrix coverage for spec-wo-evidence-wysiwyg-nav:
// /maintenance/* are the only public URLs for the four maintenance areas; the
// old /dashboard/* paths may remain ONLY in the intentional exceptions below.

const MAINTENANCE_AREAS = ["workorders", "preventive", "sparepart-requests", "stock"] as const;

const webRoot = path.resolve(__dirname, "../../..");

/**
 * next.config.mjs is exported through `withNextIntl(nextConfig)`; the plugin
 * preserves the top-level config keys, so redirects()/rewrites() are callable.
 */
interface RouteEntry {
  source: string;
  destination: string;
  permanent?: boolean;
}
type NextConfigWithRoutes = {
  redirects?: () => RouteEntry[] | Promise<RouteEntry[]>;
  rewrites?: () => RouteEntry[] | Promise<RouteEntry[]>;
};

async function loadNextConfig() {
  const mod = (await import("../../../next.config.mjs")) as { default: unknown };
  const config = mod.default as NextConfigWithRoutes;
  const redirects = config.redirects;
  const rewrites = config.rewrites;
  if (!redirects || !rewrites) throw new Error("next.config lost redirects()/rewrites(); update this test");
  return { redirects: redirects as () => Promise<RouteEntry[]>, rewrites: rewrites as () => Promise<RouteEntry[]> };
}

function walk(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(full));
    else if (/\.tsx?$/.test(entry.name)) out.push(full);
  }
  return out;
}

describe("next.config maintenance alias contract", () => {
  it("redirects every old /dashboard/<area> path to /maintenance/<area>, non-permanent, locale-gated", async () => {
    const { redirects } = await loadNextConfig();
    const list = await redirects();
    for (const area of MAINTENANCE_AREAS) {
      const entry = list.find((r) => r.source === `/:locale(id|en)/dashboard/${area}/:path*`);
      expect(entry, `redirect for ${area}`).toBeDefined();
      expect(entry?.destination).toBe(`/:locale/maintenance/${area}/:path*`);
      // 307, not 308: WA links/bookmarks must keep working even if the alias moves.
      expect(entry?.permanent).toBe(false);
    }
    // The pre-existing `/dashboard` -> operations-overview redirect is untouched.
    expect(list.some((r) => r.source === "/:locale(id|en)/dashboard")).toBe(true);
  });

  it("rewrites /maintenance/<area> to the internal dashboard file location, locale-gated", async () => {
    const { rewrites } = await loadNextConfig();
    const list = await rewrites();
    for (const area of MAINTENANCE_AREAS) {
      const entry = list.find((r) => r.source === `/:locale(id|en)/maintenance/${area}/:path*`);
      expect(entry, `rewrite for ${area}`).toBeDefined();
      // `:locale(id|en)` gate: unknown locales like /fr can never be rewritten.
      expect(entry?.destination).toBe(`/:locale/dashboard/${area}/:path*`);
    }
    // Non-maintenance aliases are unaffected.
    expect(list.some((r) => r.source === "/:locale(id|en)/master-data/:path*")).toBe(true);
    expect(list.some((r) => r.source === "/:locale(id|en)/alerts")).toBe(true);
  });
});

describe("public href contract", () => {
  it("sidebar Pemeliharaan group points at /maintenance/*", () => {
    const group = sidebarItems.find((g) => g.labelKey === "groups.maintenance");
    const urls = (group?.items ?? []).map((i) => i.url);
    expect(urls).toEqual(MAINTENANCE_AREAS.map((a) => `/maintenance/${a}`));
  });

  // Matrix row "Link internal ... langsung ke /maintenance/*": no shipped
  // string literal may still point at the internal dashboard tree. (Legacy
  // alias pages now redirect to /maintenance/* directly — no exceptions left.)
  it("no src string literal targets /dashboard/<area> anymore", () => {
    const allowed = new Set([
      // Pure transform-input test strings, never navigation targets.
      path.join(webRoot, "src/i18n/routing.test.ts"),
    ]);
    const offenders: string[] = [];
    for (const file of walk(path.join(webRoot, "src"))) {
      if (allowed.has(file)) continue;
      const text = readFileSync(file, "utf8");
      // A quote/backtick directly before `/dashboard/<area>` means a client-side
      // href/url literal; `/api/v1/dashboard/...` and `/en/dashboard/...` do not match.
      if (/["`]\/dashboard\/(workorders|preventive|sparepart-requests|stock)\b/.test(text)) {
        offenders.push(path.relative(webRoot, file));
      }
    }
    expect(offenders).toEqual([]);
  });
});
