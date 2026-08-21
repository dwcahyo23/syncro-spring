import { useQuery } from "@tanstack/react-query";

import {
  createHealthRequestSignal,
  HEALTH_REQUEST_TIMEOUT_MS,
  SYSTEM_HEALTH_REFRESH_INTERVAL_MS,
} from "@/features/system-health/hooks/use-actuator-health-query";
import type { IngestWorkerStatus } from "@/features/system-health/types";
import { API_BASE_URL } from "@/lib/api/orval-mutator";
import { expireAuthSession, getAuthToken } from "@/lib/auth/auth-client";

export async function fetchIngestWorkerStatus(signal?: AbortSignal): Promise<IngestWorkerStatus> {
  const token = getAuthToken();
  const headers = new Headers();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(`${API_BASE_URL}/api/v1/telemetry/ingest/status`, {
    headers,
    signal: createHealthRequestSignal(signal, HEALTH_REQUEST_TIMEOUT_MS),
  });
  if (response.status === 401) {
    expireAuthSession();
    throw new Error("Ingest worker status fetch failed: 401");
  }
  if (!response.ok) {
    throw new Error(`Ingest worker status fetch failed: ${response.status}`);
  }
  return response.json() as Promise<IngestWorkerStatus>;
}

export function useIngestWorkerStatus() {
  return useQuery({
    queryKey: ["ingest-worker-status"] as const,
    queryFn: ({ signal }) => fetchIngestWorkerStatus(signal),
    refetchInterval: SYSTEM_HEALTH_REFRESH_INTERVAL_MS,
    retry: 2,
  });
}
