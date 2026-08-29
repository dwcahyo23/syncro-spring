"use client";

import { useQuery } from "@tanstack/react-query";

import { syncroFetch } from "@/lib/api/orval-mutator";

/**
 * MTBF/MTTR dashboard contract (story 14-2, FR-173). Endpoint absent from the committed
 * OpenAPI snapshot, so this is a hand-written hook following the 14-1 dashboard pattern.
 * All computation is backend-owned; this hook only renders. Gated on
 * {@code enabled: Boolean(scope)} so no fetch races the plant-scope store.
 */

export interface MtbfView {
  status: "AVAILABLE" | "INSUFFICIENT_DATA";
  valueHours: number | null;
  workorderCount: number;
}

export interface MttrView {
  status: "AVAILABLE" | "INSUFFICIENT_DATA";
  valueHours: number | null;
  workorderCount: number;
}

export interface MtbfMttrResponse {
  mtbf: MtbfView;
  mttr: MttrView;
  windowFrom: string;
  windowTo: string;
  computedAt: string;
  cacheAgeMs: number | null;
  stale: boolean;
}

export function useMtbfMttr(plantId?: string, enabled?: boolean) {
  const url = "/api/v1/dashboard/mtbf-mttr";
  const qs = plantId ? `?plantId=${encodeURIComponent(plantId)}` : "";

  return useQuery<MtbfMttrResponse>({
    queryKey: [url, plantId ?? "all"],
    queryFn: async () => {
      const response = await syncroFetch<{ data: MtbfMttrResponse }>(`${url}${qs}`, { method: "GET" });
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
