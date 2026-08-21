import { useQuery } from "@tanstack/react-query";

import {
  createHealthRequestSignal,
  HEALTH_REQUEST_TIMEOUT_MS,
  SYSTEM_HEALTH_REFRESH_INTERVAL_MS,
} from "@/features/system-health/hooks/use-actuator-health-query";
import type { TelemetryFreshnessStatus } from "@/features/system-health/types";
import { API_BASE_URL } from "@/lib/api/orval-mutator";
import { expireAuthSession, getAuthToken } from "@/lib/auth/auth-client";

export async function fetchTelemetryFreshness(signal?: AbortSignal): Promise<TelemetryFreshnessStatus> {
  const token = getAuthToken();
  const headers = new Headers();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(`${API_BASE_URL}/api/v1/telemetry/freshness`, {
    headers,
    signal: createHealthRequestSignal(signal, HEALTH_REQUEST_TIMEOUT_MS),
  });
  if (response.status === 401) {
    expireAuthSession();
    throw new Error("Telemetry freshness fetch failed: 401");
  }
  if (!response.ok) {
    throw new Error(`Telemetry freshness fetch failed: ${response.status}`);
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new Error("Telemetry freshness response was not valid JSON");
  }
  const raw = body as TelemetryFreshnessStatus | null;
  if (
    !raw ||
    (raw.status !== "NO_DATA" && raw.status !== "LIVE" && raw.status !== "STALE") ||
    typeof raw.statusLabel !== "string" ||
    typeof raw.statusSeverity !== "string" ||
    typeof raw.timestamp !== "string"
  ) {
    throw new Error("Telemetry freshness response was not a freshness payload");
  }
  return raw;
}

export function useTelemetryFreshness() {
  return useQuery({
    queryKey: ["telemetry-freshness"] as const,
    queryFn: ({ signal }) => fetchTelemetryFreshness(signal),
    refetchInterval: SYSTEM_HEALTH_REFRESH_INTERVAL_MS,
    retry: 2,
  });
}
