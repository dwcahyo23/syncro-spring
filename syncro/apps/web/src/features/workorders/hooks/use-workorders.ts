"use client";

import { useQuery } from "@tanstack/react-query";

import type { WorkOrderListParams, WorkOrderPage } from "@/features/workorders/types";
import { syncroFetch } from "@/lib/api/orval-mutator";

/**
 * Fetches a server-paginated page of workorders (workorder-table story). The query key
 * includes every filter so TanStack Query refetches on any param change. staleTime 15s
 * matches the spec — short enough that a quick tab switch returns fresh data, long enough
 * to avoid a flicker on every keystroke in the search box (300ms debounce is in the
 * component, not the hook).
 */
export function useWorkorders(params: WorkOrderListParams) {
  const url = "/api/v1/workorders";
  const searchParams = new URLSearchParams();
  searchParams.set("page", String(params.page));
  searchParams.set("size", String(params.size));
  if (params.from) searchParams.set("from", params.from);
  if (params.to) searchParams.set("to", params.to);
  if (params.status) searchParams.set("status", params.status);
  if (params.machineId) searchParams.set("machineId", params.machineId);
  if (params.search) searchParams.set("search", params.search);
  const qs = searchParams.toString();

  return useQuery<WorkOrderPage>({
    queryKey: [url, params],
    queryFn: async () => {
      const response = await syncroFetch<{ data: WorkOrderPage }>(`${url}?${qs}`, { method: "GET" });
      return response.data;
    },
    staleTime: 15_000,
    retry: false,
  });
}
