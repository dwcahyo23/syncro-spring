import { useQuery } from "@tanstack/react-query";

import {
  createHealthRequestSignal,
  HEALTH_REQUEST_TIMEOUT_MS,
  SYSTEM_HEALTH_REFRESH_INTERVAL_MS,
} from "@/features/system-health/hooks/use-actuator-health-query";
import type { StaleMachineItem, StaleMachineStatus } from "@/features/system-health/types";
import { API_BASE_URL } from "@/lib/api/orval-mutator";
import { expireAuthSession, getAuthToken } from "@/lib/auth/auth-client";

function isStaleMachineItem(value: unknown): value is StaleMachineItem {
  if (value == null || typeof value !== "object") {
    return false;
  }
  const item = value as Record<string, unknown>;
  return (
    typeof item.machineId === "string" &&
    typeof item.machineCode === "string" &&
    typeof item.plantCode === "string" &&
    typeof item.freshnessState === "string" &&
    typeof item.statusLabel === "string" &&
    (item.lastReceivedAt === null || typeof item.lastReceivedAt === "string")
  );
}

export async function fetchStaleMachines(signal?: AbortSignal): Promise<StaleMachineStatus> {
  const token = getAuthToken();
  const headers = new Headers();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(`${API_BASE_URL}/api/v1/telemetry/stale-machines`, {
    headers,
    signal: createHealthRequestSignal(signal, HEALTH_REQUEST_TIMEOUT_MS),
  });
  if (response.status === 401) {
    expireAuthSession();
    throw new Error("Stale machines fetch failed: 401");
  }
  if (!response.ok) {
    throw new Error(`Stale machines fetch failed: ${response.status}`);
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new Error("Stale machines response was not valid JSON");
  }
  const raw = body as StaleMachineStatus | null;
  if (
    !raw ||
    typeof raw.timestamp !== "string" ||
    typeof raw.staleMachineCount !== "number" ||
    !Array.isArray(raw.items) ||
    !raw.items.every(isStaleMachineItem)
  ) {
    throw new Error("Stale machines response was not a stale-machine payload");
  }
  return raw;
}

export function useStaleMachines() {
  return useQuery({
    queryKey: ["stale-machines"] as const,
    queryFn: ({ signal }) => fetchStaleMachines(signal),
    refetchInterval: SYSTEM_HEALTH_REFRESH_INTERVAL_MS,
    retry: 2,
  });
}
