// @vitest-environment node
import { NextRequest } from "next/server";

import { describe, expect, it, vi } from "vitest";

import { AUTH_TOKEN_COOKIE } from "./lib/auth/auth-session";

// Regression guard for spec-wo-evidence-wysiwyg-nav: `/maintenance/*` is the
// canonical public URL for the maintenance group, so the auth guard in proxy.ts
// must cover it exactly like the legacy `/dashboard/*` tree. Delete the
// `/maintenance` entries from protectedRoutes/matcher and these tests fail.
//
// next-intl's middleware factory is stubbed because its ESM build imports the
// extensionless `next/server`, which Node ESM cannot resolve outside a bundler.
// The stub is a pass-through locale negotiator: `proxy()` keeps running its
// real auth guard, which is what these assertions are about. (The live 307 +
// `next=` behavior was additionally verified against `next start`.)
vi.mock("next-intl/middleware", async () => {
  const { NextResponse } = await import("next/server");
  return {
    default: (routing: { locales: string[] }) => (request: NextRequest) => {
      const { pathname } = request.nextUrl;
      const first = pathname.split("/")[1];
      // Already locale-prefixed → negotiation is a no-op passthrough.
      if ((routing.locales as string[]).includes(first)) return NextResponse.next();
      const url = request.nextUrl.clone();
      url.pathname = `/${routing.locales[0]}${pathname === "/" ? "" : pathname}`;
      return NextResponse.redirect(url, 307);
    },
  };
});

const { default: proxy, config: proxyConfig } = await import("./proxy");

function unauthenticated(path: string) {
  return new NextRequest(`http://localhost${path}`);
}

describe("proxy auth guard for /maintenance", () => {
  it("sends an anonymous visitor to login, preserving the locale-prefixed next path", async () => {
    const response = await proxy(unauthenticated("/id/maintenance/workorders"));
    expect(response.status).toBe(307);
    const location = response.headers.get("location") ?? "";
    expect(location).toContain("/id/auth/v2/login");
    expect(location).toContain("next=%2Fid%2Fmaintenance%2Fworkorders");
  });

  it("guards deep maintenance links too (WA ack landing page)", async () => {
    const response = await proxy(unauthenticated("/id/maintenance/workorders/ack-task-list?token=X"));
    expect(response.status).toBe(307);
    // The guard's `next` param carries the pathname; the original query is
    // dropped from it but the location itself keeps the full return context
    // only through `next` — behavior shared with the legacy /dashboard tree.
    expect(response.headers.get("location") ?? "").toContain("next=%2Fid%2Fmaintenance%2Fworkorders%2Fack-task-list");
  });

  it("lets an authenticated request through without a login redirect", async () => {
    const request = unauthenticated("/id/maintenance/workorders");
    request.cookies.set(AUTH_TOKEN_COOKIE, "token-for-guard-check");
    const response = await proxy(request);
    expect(response.status).toBeLessThan(300);
  });

  it("matcher still covers the maintenance trees explicitly (dotted sheet numbers)", () => {
    expect(proxyConfig.matcher).toContain("/maintenance/:path*");
    expect(proxyConfig.matcher).toContain("/:locale/maintenance/:path*");
  });
});

// spec "Template Laporan Cetak": /report-templates is a protected Konfigurasi
// page (roles are enforced by the client RoleGuard + API gate; the auth guard
// only separates signed-in from anonymous). Delete "/report-templates" from
// protectedRoutes in proxy.ts and the redirect assertion fails.
describe("proxy auth guard for /report-templates", () => {
  it("sends an anonymous visitor of /id/report-templates to login (307), preserving the next path", async () => {
    const response = await proxy(unauthenticated("/id/report-templates"));
    expect(response.status).toBe(307);
    const location = response.headers.get("location") ?? "";
    expect(location).toContain("/id/auth/v2/login");
    expect(location).toContain("next=%2Fid%2Freport-templates");
  });

  it("lets an authenticated report-templates request through without a login redirect", async () => {
    const request = unauthenticated("/id/report-templates");
    request.cookies.set(AUTH_TOKEN_COOKIE, "token-for-guard-check");
    const response = await proxy(request);
    expect(response.status).toBeLessThan(300);
  });

  it("lists /report-templates in protectedRoutes (source-pinned: the list is module-private)", async () => {
    const source = await import("node:fs").then((fs) =>
      fs.readFileSync(new URL("./proxy.ts", import.meta.url), "utf8"),
    );
    expect(source).toMatch(/protectedRoutes\s*=\s*\[[\s\S]*?"\/report-templates"/);
    // The catch-all matcher (everything but api/_next/dotted paths) covers it.
    expect(proxyConfig.matcher[0]).toContain("(?!api|trpc|_next|_vercel");
  });
});
