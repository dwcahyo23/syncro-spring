import type { LoginResult } from "@/lib/auth/auth-session";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

type LoginInput = {
  loginIdentifier: string;
  password: string;
};

function isAuthUser(value: unknown): value is LoginResult["user"] {
  if (!value || typeof value !== "object") {
    return false;
  }
  const user = value as Record<string, unknown>;
  return (
    typeof user.id === "string" &&
    typeof user.loginIdentifier === "string" &&
    (user.applicationRole === "SUPER_ADMIN" || user.applicationRole === "MANAGE" || user.applicationRole === "VIEWER")
  );
}

function isLoginResult(value: unknown): value is LoginResult {
  if (!value || typeof value !== "object") {
    return false;
  }
  const result = value as Record<string, unknown>;
  return (
    result.tokenType === "Bearer" &&
    typeof result.accessToken === "string" &&
    typeof result.expiresInSeconds === "number" &&
    isAuthUser(result.user)
  );
}

export async function loginToSyncro(input: LoginInput): Promise<LoginResult> {
  const response = await fetch(`${API_BASE_URL}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });

  const payload: unknown = await response.json().catch(() => undefined);
  if (!response.ok) {
    throw new Error("Invalid login credentials.");
  }
  if (!isLoginResult(payload)) {
    throw new Error("Authentication response could not be verified.");
  }
  return payload;
}

export async function fetchCurrentUser(token: string): Promise<LoginResult["user"]> {
  const response = await fetch(`${API_BASE_URL}/auth/me`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const payload: unknown = await response.json().catch(() => undefined);
  if (!response.ok || !isAuthUser(payload)) {
    throw new Error("Authentication is required.");
  }
  return payload;
}
