import { useQuery } from "@tanstack/react-query";

import type { ActuatorHealthResponse } from "@/features/system-health/types";
import { API_BASE_URL } from "@/lib/api/orval-mutator";
import { expireAuthSession, getAuthToken } from "@/lib/auth/auth-client";

export const SYSTEM_HEALTH_REFRESH_INTERVAL_MS = 30_000;

export const HEALTH_REQUEST_TIMEOUT_MS = 10_000;

/** Builds an abort signal that combines the caller's signal with a hard timeout, with a manual fallback for runtimes without `AbortSignal.timeout` (Safari < 17.4). */
export function createHealthRequestSignal(signal: AbortSignal | undefined, timeoutMs: number): AbortSignal {
  if (typeof AbortSignal.timeout === "function") {
    return signal ? AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs);
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  signal?.addEventListener("abort", () => {
    clearTimeout(timer);
    controller.abort();
  });
  return controller.signal;
}

export async function fetchActuatorHealth(signal?: AbortSignal): Promise<ActuatorHealthResponse> {
  const token = getAuthToken();
  const headers = new Headers();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(`${API_BASE_URL}/actuator/health`, {
    headers,
    signal: createHealthRequestSignal(signal, HEALTH_REQUEST_TIMEOUT_MS),
  });
  if (response.status === 401) {
    expireAuthSession();
    throw new Error("Actuator health check failed: 401");
  }
  // Actuator returns HTTP 503 with a structured body when the aggregate status is DOWN.
  // Treat 503-with-body as valid data so partial-failure cards render correctly.
  if (response.status !== 200 && response.status !== 503) {
    throw new Error(`Actuator health check failed: ${response.status}`);
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new Error(`Actuator health response was not valid JSON (HTTP ${response.status})`);
  }
  if (!body || typeof (body as ActuatorHealthResponse).status !== "string") {
    throw new Error(`Actuator health response was not a health payload (HTTP ${response.status})`);
  }
  return body as ActuatorHealthResponse;
}

export function useActuatorHealthQuery() {
  return useQuery({
    queryKey: ["actuator-health"] as const,
    queryFn: ({ signal }) => fetchActuatorHealth(signal),
    refetchInterval: SYSTEM_HEALTH_REFRESH_INTERVAL_MS,
    retry: 2,
  });
}
