import { type AppLocale, isLocale, routing } from "./routing";

// Matches next-intl's default locale-cookie name (routing.localeCookie), so a
// future flip to `localeDetection: true` needs no migration.
export const NEXT_LOCALE = "NEXT_LOCALE";

/**
 * Story 23-3: the bare-root (`/`) redirect honors the switcher's cookie.
 * Unknown or absent values fall back to the default locale (23-1 behavior);
 * explicit prefixed URLs never consult this — the URL owns the locale.
 */
export function rootLocaleFromCookie(rawCookieValue: string | undefined): AppLocale {
  return rawCookieValue && isLocale(rawCookieValue) ? rawCookieValue : routing.defaultLocale;
}
