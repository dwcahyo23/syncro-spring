"use client";

import { AUTH_TOKEN_COOKIE, AUTH_USER_COOKIE, type AuthUser } from "@/lib/auth/auth-session";
import { notifyAuthSessionChanged } from "@/lib/auth/use-auth-user";
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

export function redirectToLogin(nextPath?: string) {
  const targetPath = nextPath ?? `${window.location.pathname}${window.location.search}`;
  window.location.assign(`/auth/v2/login?next=${encodeURIComponent(targetPath)}`);
}

export function expireAuthSession() {
  clearAuthSession();
  redirectToLogin();
}
