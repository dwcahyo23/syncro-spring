"use client";

import { AUTH_TOKEN_COOKIE, AUTH_USER_COOKIE, type AuthUser } from "@/lib/auth/auth-session";
import { deleteClientCookie, getClientCookie, setClientCookie } from "@/lib/cookie.client";

export function saveAuthSession(accessToken: string, expiresInSeconds: number, user: AuthUser) {
  const days = Math.max(1, Math.ceil(expiresInSeconds / 86400));
  setClientCookie(AUTH_TOKEN_COOKIE, accessToken, days);
  setClientCookie(AUTH_USER_COOKIE, JSON.stringify(user), days);
}

export function clearAuthSession() {
  deleteClientCookie(AUTH_TOKEN_COOKIE);
  deleteClientCookie(AUTH_USER_COOKIE);
}

export function getAuthToken() {
  return getClientCookie(AUTH_TOKEN_COOKIE);
}
