"use client";

import { useQuery } from "@tanstack/react-query";

import { syncroFetch } from "@/lib/api/orval-mutator";

/**
 * Machine dashboard contract (story 14-1, FR-170). Endpoint absent from the committed
 * OpenAPI snapshot, so this is a hand-written hook following the use-workorders pattern.
 * All counts are backend-computed. Gated on {@code enabled: Boolean(scope)} so no fetch
 * races the plant-scope store.
 */

export interface MachineDashboardTelemetryState {
  freshnessState: string | null;
  running: boolean | null;
  runtimeHours: number | null;
  counting: number | null;
  lastReceivedAt: string | null;
}

export interface MachineDashboardLifetimeRisk {
  maxConsumedPercentage: string | null;
  thresholdPercentage: string | null;
  status: string;
}

export interface MachineDashboardRow {
  machineId: string;
  code: string;
  name: string | null;
  status: string;
  machineGroupName: string | null;
  plantCode: string;
  plantName: string;
  openWorkOrderCount: number;
  openAlertCount: number;
  telemetryFreshness: MachineDashboardTelemetryState | null;
  lifetimeRisk: MachineDashboardLifetimeRisk;
}

export interface MachineDashboardResponse {
  items: MachineDashboardRow[];
}

export function useMachineDashboard(plantId?: string, enabled?: boolean) {
  const url = "/api/v1/dashboard/machines";
  const qs = plantId ? `?plantId=${encodeURIComponent(plantId)}` : "";

  return useQuery<MachineDashboardResponse>({
    queryKey: [url, plantId ?? "all"],
    queryFn: async () => {
      const response = await syncroFetch<{ data: MachineDashboardResponse }>(`${url}${qs}`, { method: "GET" });
      return response.data;
    },
    enabled: enabled ?? true,
    staleTime: 15_000,
    retry: false,
  });
}
