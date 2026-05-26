"use client";

import { useSyncExternalStore } from "react";

import { AUTH_USER_COOKIE, type AuthUser } from "@/lib/auth/auth-session";
import { getClientCookie } from "@/lib/cookie.client";

const APPLICATION_ROLES = new Set<AuthUser["applicationRole"]>(["SUPER_ADMIN", "MANAGE", "VIEWER"]);
const AUTH_SESSION_EVENT = "syncro-auth-session-changed";

let cachedAuthUserCookie: string | undefined;
let cachedAuthUser: AuthUser | null = null;

export function notifyAuthSessionChanged() {
  window.dispatchEvent(new Event(AUTH_SESSION_EVENT));
}

export function readAuthUser(): AuthUser | null {
  const value = getClientCookie(AUTH_USER_COOKIE);

  if (value === cachedAuthUserCookie) {
    return cachedAuthUser;
  }

  cachedAuthUserCookie = value;

  if (!value) {
    cachedAuthUser = null;
    return cachedAuthUser;
  }

  try {
    const parsed: unknown = JSON.parse(value);

    if (!isAuthUser(parsed)) {
      cachedAuthUser = null;
      return cachedAuthUser;
    }

    cachedAuthUser = parsed;
    return cachedAuthUser;
  } catch {
    cachedAuthUser = null;
    return cachedAuthUser;
  }
}

export function useAuthUser() {
  return useSyncExternalStore(subscribeToAuthSession, readAuthUser, () => null);
}

function subscribeToAuthSession(onStoreChange: () => void) {
  window.addEventListener(AUTH_SESSION_EVENT, onStoreChange);
  window.addEventListener("storage", onStoreChange);

  return () => {
    window.removeEventListener(AUTH_SESSION_EVENT, onStoreChange);
    window.removeEventListener("storage", onStoreChange);
  };
}

function isAuthUser(value: unknown): value is AuthUser {
  if (!value || typeof value !== "object") {
    return false;
  }

  const user = value as Record<string, unknown>;

  return (
    typeof user.id === "string" &&
    typeof user.loginIdentifier === "string" &&
    typeof user.applicationRole === "string" &&
    APPLICATION_ROLES.has(user.applicationRole as AuthUser["applicationRole"])
  );
}
