import { describe, expect, it } from "vitest";

import { NEXT_LOCALE, rootLocaleFromCookie } from "./switch-locale";

describe("NEXT_LOCALE cookie name", () => {
  it("matches next-intl's default locale-cookie name", () => {
    expect(NEXT_LOCALE).toBe("NEXT_LOCALE");
  });
});

describe("rootLocaleFromCookie (23-3 root-redirect matrix)", () => {
  it("honors a supported cookie value (en wins over the default)", () => {
    expect(rootLocaleFromCookie("en")).toBe("en");
    expect(rootLocaleFromCookie("id")).toBe("id");
  });

  it("ignores an unsupported value and falls back to the default locale", () => {
    expect(rootLocaleFromCookie("xx")).toBe("id");
    expect(rootLocaleFromCookie("id-ID")).toBe("id");
    expect(rootLocaleFromCookie("EN")).toBe("id");
  });

  it("falls back to the default locale when the cookie is absent or empty", () => {
    expect(rootLocaleFromCookie(undefined)).toBe("id");
    expect(rootLocaleFromCookie("")).toBe("id");
  });
});
