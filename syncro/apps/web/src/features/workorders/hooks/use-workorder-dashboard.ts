"use client";

import { useQuery } from "@tanstack/react-query";

import { syncroFetch } from "@/lib/api/orval-mutator";

/**
 * Workorder dashboard contract (story 14-1, FR-171). Endpoint absent from the committed
 * OpenAPI snapshot, so this is a hand-written hook following the use-workorders pattern.
 * All counts are backend-computed. Supports plant/section/status/category filters.
 * Gated on {@code enabled: Boolean(scope)} so no fetch races the plant-scope store.
 */

export interface StatusCount {
  status: string;
  count: number;
}

export interface CategoryCount {
  categoryCode: string | null;
  categoryLabel: string | null;
  count: number;
}

export interface MonthlyWorkorderCount {
  month: number;
  openCount: number;
  closeCount: number;
}

export interface WorkorderDashboardResponse {
  total: number;
  byStatus: StatusCount[];
  byCategory: CategoryCount[];
  byMonth: MonthlyWorkorderCount[];
}

export interface WorkorderDashboardParams {
  plantId?: string;
  sectionId?: string;
  status?: string;
  categoryCode?: string;
}

export function useWorkorderDashboard(params: WorkorderDashboardParams = {}, enabled?: boolean) {
  const url = "/api/v1/dashboard/workorders";
  const searchParams = new URLSearchParams();
  if (params.plantId) searchParams.set("plantId", params.plantId);
  if (params.sectionId) searchParams.set("sectionId", params.sectionId);
  if (params.status) searchParams.set("status", params.status);
  if (params.categoryCode) searchParams.set("categoryCode", params.categoryCode);
  const qs = searchParams.toString();

  return useQuery<WorkorderDashboardResponse>({
    queryKey: [url, params],
    queryFn: async () => {
      const response = await syncroFetch<{ data: WorkorderDashboardResponse }>(
        `${url}${qs ? `?${qs}` : ""}`,
        { method: "GET" },
      );
      return response.data;
    },
    enabled: enabled ?? true,
    staleTime: 15_000,
    retry: false,
  });
}
