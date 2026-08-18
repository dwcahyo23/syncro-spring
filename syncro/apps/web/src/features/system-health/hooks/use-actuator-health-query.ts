import { useQuery } from "@tanstack/react-query";

import { getAuthToken } from "@/lib/auth/auth-client";
import { API_BASE_URL } from "@/lib/api/orval-mutator";
import type { ActuatorHealthResponse } from "@/features/system-health/types";

export const SYSTEM_HEALTH_REFRESH_INTERVAL_MS = 30_000;

async function fetchActuatorHealth(): Promise<ActuatorHealthResponse> {
  const token = getAuthToken();
  const headers = new Headers();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(`${API_BASE_URL}/actuator/health`, { headers });
  if (!response.ok) {
    throw new Error(`Actuator health check failed: ${response.status}`);
  }
  return response.json() as Promise<ActuatorHealthResponse>;
}

export function useActuatorHealthQuery() {
  return useQuery({
    queryKey: ["actuator-health"] as const,
    queryFn: fetchActuatorHealth,
    refetchInterval: SYSTEM_HEALTH_REFRESH_INTERVAL_MS,
    retry: 2,
  });
}
