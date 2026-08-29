"use client";

import { useQuery } from "@tanstack/react-query";

import { syncroFetch } from "@/lib/api/orval-mutator";

/**
 * Technician KPI dashboard contract (story 14-2, FR-174). Endpoint absent from the
 * committed OpenAPI snapshot, so this is a hand-written hook following the 14-1
 * dashboard pattern. All computation is backend-owned; this hook only renders.
 * Gated on {@code enabled: Boolean(scope)} so no fetch races the plant-scope store.
 */

export interface RatingDimensionView {
  dimensionId: string;
  dimensionCode: string;
  dimensionLabel: string;
  averageScore: number | null;
}

export interface TechnicianKpiRowView {
  technicianId: string;
  technicianName: string;
  completedCount: number;
  averageMttrHours: number | null;
  onTimePercentage: number | null;
  ratings: RatingDimensionView[];
}

export interface TechnicianKpiResponse {
  technicians: TechnicianKpiRowView[];
  windowFrom: string;
  windowTo: string;
  computedAt: string;
  cacheAgeMs: number | null;
  stale: boolean;
}

export function useTechnicianKpi(plantId?: string, enabled?: boolean) {
  const url = "/api/v1/dashboard/technician-kpi";
  const qs = plantId ? `?plantId=${encodeURIComponent(plantId)}` : "";

  return useQuery<TechnicianKpiResponse>({
    queryKey: [url, plantId ?? "all"],
    queryFn: async () => {
      const response = await syncroFetch<{ data: TechnicianKpiResponse }>(`${url}${qs}`, { method: "GET" });
      return response.data;
    },
    enabled: enabled ?? true,
    // Aligned to the backend analytics TTL (SYNCRO_DASHBOARD_ANALYTICS_TTL default PT30M);
    // the frontend keeps data fresh for half the server TTL so a client rarely shows a
    // stale (recomputing) payload.
    staleTime: 15 * 60 * 1000,
    retry: false,
  });
}
