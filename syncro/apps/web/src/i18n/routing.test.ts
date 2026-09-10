import { describe, expect, it } from "vitest";

import { isLocale, routing, stripLocale } from "./routing";

describe("i18n routing config", () => {
  it("serves exactly the id and en locales", () => {
    expect(routing.locales).toEqual(["id", "en"]);
  });

  it("defaults to Indonesian", () => {
    expect(routing.defaultLocale).toBe("id");
  });

  it("always prefixes the locale in public URLs", () => {
    expect(routing.localePrefix).toBe("always");
  });

  it("does not negotiate from accept-language (URL owns the locale)", () => {
    expect(routing.localeDetection).toBe(false);
  });

  // Story 23-3: the language switcher is the sole NEXT_LOCALE writer; next-intl's
  // middleware syncCookie and client syncLocaleCookie must stay off (they would
  // flip/downgrade the long-lived cookie to the last-visited URL's locale).
  it("keeps next-intl from writing the locale cookie", () => {
    expect(routing.localeCookie).toBe(false);
  });
});

describe("isLocale", () => {
  it("accepts supported locales only", () => {
    expect(isLocale("id")).toBe(true);
    expect(isLocale("en")).toBe(true);
    expect(isLocale("fr")).toBe(false);
    expect(isLocale("id-ID")).toBe(false);
  });
});

describe("stripLocale", () => {
  it("strips a supported prefix so the auth guard sees the original path", () => {
    expect(stripLocale("/id/alerts")).toBe("/alerts");
    expect(stripLocale("/en/dashboard/workorders/1")).toBe("/dashboard/workorders/1");
    expect(stripLocale("/id")).toBe("/");
    expect(stripLocale("/id/")).toBe("/");
  });

  it("leaves unprefixed and foreign-locale paths untouched", () => {
    expect(stripLocale("/alerts")).toBe("/alerts");
    expect(stripLocale("/fr/alerts")).toBe("/fr/alerts");
    expect(stripLocale("/")).toBe("/");
  });
});
