import { defineRouting } from "next-intl/routing";

export const routing = defineRouting({
  locales: ["id", "en"],
  defaultLocale: "id",
  localePrefix: "always",
  // The URL (and 23.3's switcher cookie/persistence) owns the locale; / always
  // lands on the default locale instead of Accept-Language guessing.
  localeDetection: false,
});

export type AppLocale = (typeof routing.locales)[number];

export function isLocale(value: string): value is AppLocale {
  return (routing.locales as readonly string[]).includes(value);
}

/** Strips a leading supported-locale segment so route guards see the original path. */
export function stripLocale(pathname: string): string {
  const [, first] = pathname.split("/");
  if (first && isLocale(first)) {
    const rest = pathname.slice(first.length + 1);
    return rest === "" ? "/" : rest;
  }
  return pathname;
}
