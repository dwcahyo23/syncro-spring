"use client";

import { useQuery } from "@tanstack/react-query";

import type { KanbanView } from "@/features/workorders/types";
import { syncroFetch } from "@/lib/api/orval-mutator";

/** Fetches the kanban board data (scope-filtered, grouped by status with embedded todos). */
export function useKanban() {
  const url = "/api/v1/workorders/kanban";
  return useQuery<KanbanView>({
    queryKey: [url],
    queryFn: async () => {
      const response = await syncroFetch<{ data: KanbanView }>(url, { method: "GET" });
      return response.data;
    },
    staleTime: 30_000,
    retry: false,
  });
}
