import { describe, expect, it } from "vitest";

import { buildLoginUrl } from "@/lib/auth/auth-client";

// 401-expiry must land on the CURRENT locale's login page; an unprefixed URL
// would make next-intl re-prefix to the default /id and silently switch an /en
// user's language mid-session.
describe("buildLoginUrl locale awareness", () => {
  it("keeps an /en visitor on the English login page", () => {
    const url = buildLoginUrl("/en/dashboard/workorders", "/en/dashboard/workorders");
    expect(url.startsWith("/en/auth/v2/login?next=")).toBe(true);
    expect(decodeURIComponent(url)).toContain("/en/dashboard/workorders");
  });

  it("falls back to the default locale for unprefixed paths", () => {
    const url = buildLoginUrl("/some-legacy/path", "/some-legacy/path");
    expect(url.startsWith("/id/auth/v2/login?next=")).toBe(true);
  });
});
