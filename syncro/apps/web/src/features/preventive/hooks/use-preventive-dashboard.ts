"use client";

import { useQuery } from "@tanstack/react-query";

import { syncroFetch } from "@/lib/api/orval-mutator";

/**
 * Preventive dashboard contract (story 14-1, FR-172). Endpoint absent from the committed
 * OpenAPI snapshot, so this is a hand-written hook following the use-workorders pattern.
 * Due/overdue derive from the server clock, never client time.
 * Gated on {@code enabled: Boolean(scope)} so no fetch races the plant-scope store.
 */

export interface PreventiveUpcomingRow {
  scheduleId: string;
  machineId: string;
  programId: string;
  dueDate: string;
  status: string;
  derivedStatus: string;
  category: string;
  scheduleType: string;
  machineCode: string;
  machineName: string | null;
  programTitle: string;
}

export interface PreventiveDashboardResponse {
  dueCount: number;
  overdueCount: number;
  upcoming: PreventiveUpcomingRow[];
}

export function usePreventiveDashboard(plantId?: string, enabled?: boolean) {
  const url = "/api/v1/dashboard/preventive";
  const qs = plantId ? `?plantId=${encodeURIComponent(plantId)}` : "";

  return useQuery<PreventiveDashboardResponse>({
    queryKey: [url, plantId ?? "all"],
    queryFn: async () => {
      const response = await syncroFetch<{ data: PreventiveDashboardResponse }>(`${url}${qs}`, { method: "GET" });
      return response.data;
    },
    enabled: enabled ?? true,
    staleTime: 15_000,
    retry: false,
  });
}
