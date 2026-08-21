import { useQuery } from "@tanstack/react-query";

import {
  createHealthRequestSignal,
  HEALTH_REQUEST_TIMEOUT_MS,
  SYSTEM_HEALTH_REFRESH_INTERVAL_MS,
} from "@/features/system-health/hooks/use-actuator-health-query";
import type { NotificationWorkerStatus } from "@/features/system-health/types";
import { API_BASE_URL } from "@/lib/api/orval-mutator";
import { expireAuthSession, getAuthToken } from "@/lib/auth/auth-client";

export async function fetchNotificationWorkerStatus(signal?: AbortSignal): Promise<NotificationWorkerStatus> {
  const token = getAuthToken();
  const headers = new Headers();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(`${API_BASE_URL}/api/v1/notification/worker/status`, {
    headers,
    signal: createHealthRequestSignal(signal, HEALTH_REQUEST_TIMEOUT_MS),
  });
  if (response.status === 401) {
    expireAuthSession();
    throw new Error("Notification worker status fetch failed: 401");
  }
  if (!response.ok) {
    throw new Error(`Notification worker status fetch failed: ${response.status}`);
  }
  return response.json() as Promise<NotificationWorkerStatus>;
}

export function useNotificationWorkerStatus() {
  return useQuery({
    queryKey: ["notification-worker-status"] as const,
    queryFn: ({ signal }) => fetchNotificationWorkerStatus(signal),
    refetchInterval: SYSTEM_HEALTH_REFRESH_INTERVAL_MS,
    retry: 2,
  });
}
