"use client";

import { AUTH_TOKEN_COOKIE, AUTH_USER_COOKIE, type AuthUser } from "@/lib/auth/auth-session";
import { notifyAuthSessionChanged } from "@/lib/auth/use-auth-user";
import { isLocale, routing } from "@/i18n/routing";
import { deleteClientCookie, getClientCookie, setClientCookie } from "@/lib/cookie.client";

export function saveAuthSession(accessToken: string, expiresInSeconds: number, user: AuthUser) {
  const days = Math.max(1, Math.ceil(expiresInSeconds / 86400));
  setClientCookie(AUTH_TOKEN_COOKIE, accessToken, days);
  setClientCookie(AUTH_USER_COOKIE, JSON.stringify(user), days);
  notifyAuthSessionChanged();
}

export function clearAuthSession() {
  deleteClientCookie(AUTH_TOKEN_COOKIE);
  deleteClientCookie(AUTH_USER_COOKIE);
  notifyAuthSessionChanged();
}

export function getAuthToken() {
  return getClientCookie(AUTH_TOKEN_COOKIE);
}

/**
 * Build the login URL for the locale active at `currentPathname`. Under
 * localePrefix "always" the login page only exists at /<locale>/auth/v2/login;
 * an unprefixed URL would make next-intl re-prefix to the default locale and
 * silently switch an /en user's language on session expiry.
 */
export function buildLoginUrl(currentPathname: string, nextPath: string): string {
  const [, first] = currentPathname.split("/");
  const locale = isLocale(first) ? first : routing.defaultLocale;
  return `/${locale}/auth/v2/login?next=${encodeURIComponent(nextPath)}`;
}

export function redirectToLogin(nextPath?: string) {
  const targetPath = nextPath ?? `${window.location.pathname}${window.location.search}`;
  window.location.assign(buildLoginUrl(window.location.pathname, targetPath));
}

export function expireAuthSession() {
  clearAuthSession();
  redirectToLogin();
}
