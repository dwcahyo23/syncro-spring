import { expireAuthSession } from "@/lib/auth/auth-client";
import type { LoginResult } from "@/lib/auth/auth-session";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

type LoginInput = {
  loginIdentifier: string;
  password: string;
};

export type PlantScope = {
  id: string;
  code: string;
  name: string;
};

export type PlantScopeResponse = {
  mode: "UNRESTRICTED" | "ASSIGNED" | "EMPTY";
  availablePlants: PlantScope[];
  defaultPlantId: string | null;
  emptyReason: "NO_PLANTS_ASSIGNED" | null;
};

function isAuthUser(value: unknown): value is LoginResult["user"] {
  if (!value || typeof value !== "object") {
    return false;
  }
  const user = value as Record<string, unknown>;
  return (
    typeof user.id === "string" &&
    typeof user.loginIdentifier === "string" &&
    (user.applicationRole === "SUPER_ADMIN" ||
      user.applicationRole === "MANAGER_MAINTENANCE" ||
      user.applicationRole === "MAINTENANCE_LEADER" ||
      user.applicationRole === "SECTION_LEADER" ||
      user.applicationRole === "STAFF_MAINTENANCE" ||
      user.applicationRole === "TECHNICIAN" ||
      user.applicationRole === "INVENTORY_MAINTENANCE" ||
      user.applicationRole === "STOREKEEPER" ||
      user.applicationRole === "PRODUCTION_LEADER" ||
      user.applicationRole === "AUDITOR")
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

function isPlantScope(value: unknown): value is PlantScope {
  if (!value || typeof value !== "object") {
    return false;
  }
  const plant = value as Record<string, unknown>;
  return typeof plant.id === "string" && typeof plant.code === "string" && typeof plant.name === "string";
}

function isPlantScopeResponse(value: unknown): value is PlantScopeResponse {
  if (!value || typeof value !== "object") {
    return false;
  }
  const scope = value as Record<string, unknown>;
  return (
    (scope.mode === "UNRESTRICTED" || scope.mode === "ASSIGNED" || scope.mode === "EMPTY") &&
    Array.isArray(scope.availablePlants) &&
    scope.availablePlants.every(isPlantScope) &&
    (typeof scope.defaultPlantId === "string" || scope.defaultPlantId === null) &&
    (scope.emptyReason === "NO_PLANTS_ASSIGNED" || scope.emptyReason === null)
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
  if (response.status === 401) {
    expireAuthSession();
    throw new Error("Authentication is required.");
  }
  if (!response.ok || !isAuthUser(payload)) {
    throw new Error("Authentication is required.");
  }
  return payload;
}

export async function fetchPlantScope(token: string): Promise<PlantScopeResponse> {
  const response = await fetch(`${API_BASE_URL}/auth/plant-scope`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const payload: unknown = await response.json().catch(() => undefined);
  if (response.status === 401) {
    expireAuthSession();
    throw new Error("Authentication is required.");
  }
  if (!response.ok || !isPlantScopeResponse(payload)) {
    throw new Error("Plant scope could not be loaded.");
  }
  return payload;
}

export async function logoutFromSyncro(token: string | undefined) {
  await fetch(`${API_BASE_URL}/auth/logout`, {
    method: "POST",
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  }).catch(() => undefined);
}
