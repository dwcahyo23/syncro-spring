import { useQuery } from "@tanstack/react-query";

import {
  createHealthRequestSignal,
  HEALTH_REQUEST_TIMEOUT_MS,
  SYSTEM_HEALTH_REFRESH_INTERVAL_MS,
} from "@/features/system-health/hooks/use-actuator-health-query";
import type { TelemetryDataQualityStatus } from "@/features/system-health/types";
import { API_BASE_URL } from "@/lib/api/orval-mutator";
import { expireAuthSession, getAuthToken } from "@/lib/auth/auth-client";

export async function fetchDataQuality(signal?: AbortSignal): Promise<TelemetryDataQualityStatus> {
  const token = getAuthToken();
  const headers = new Headers();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(`${API_BASE_URL}/api/v1/telemetry/data-quality`, {
    headers,
    signal: createHealthRequestSignal(signal, HEALTH_REQUEST_TIMEOUT_MS),
  });
  if (response.status === 401) {
    expireAuthSession();
    throw new Error("Telemetry data-quality fetch failed: 401");
  }
  if (!response.ok) {
    throw new Error(`Telemetry data-quality fetch failed: ${response.status}`);
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new Error("Telemetry data-quality response was not valid JSON");
  }
  const raw = body as TelemetryDataQualityStatus | null;
  if (
    !raw ||
    (raw.status !== "GOOD" && raw.status !== "DEGRADED" && raw.status !== "CRITICAL") ||
    typeof raw.statusLabel !== "string" ||
    typeof raw.statusSeverity !== "string" ||
    typeof raw.timestamp !== "string" ||
    !Number.isFinite(raw.windowSeconds) ||
    !Number.isFinite(raw.quarantinedCount) ||
    !Number.isFinite(raw.rejectionRatePct) ||
    !Number.isFinite(raw.anomalyCount) ||
    !Number.isFinite(raw.deadLetterCount) ||
    !Number.isFinite(raw.receivedCount) ||
    typeof raw.quarantinedSeverity !== "string" ||
    typeof raw.rejectionRateSeverity !== "string" ||
    typeof raw.anomalySeverity !== "string" ||
    typeof raw.deadLetterSeverity !== "string" ||
    (raw.latencyState !== "NO_DATA" &&
      raw.latencyState !== "NORMAL" &&
      raw.latencyState !== "ELEVATED" &&
      raw.latencyState !== "CRITICAL") ||
    typeof raw.latencySeverity !== "string" ||
    (raw.lastLatencyMs !== null && !Number.isFinite(raw.lastLatencyMs))
  ) {
    throw new Error("Telemetry data-quality response was not a data-quality payload");
  }
  return raw;
}

export function useDataQuality() {
  return useQuery({
    queryKey: ["telemetry-data-quality"] as const,
    queryFn: ({ signal }) => fetchDataQuality(signal),
    refetchInterval: SYSTEM_HEALTH_REFRESH_INTERVAL_MS,
    retry: 2,
  });
}
