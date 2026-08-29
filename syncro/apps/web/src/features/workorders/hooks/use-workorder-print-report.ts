"use client";

import { useQuery } from "@tanstack/react-query";

import type { WorkorderPrintReportView } from "@/features/workorders/print-types";
import { syncroFetch } from "@/lib/api/orval-mutator";

/** Reads the aggregate workorder print report (story 14-3, FR-175). */
export function useWorkorderPrintReport(workOrderId: string | null) {
  return useQuery<WorkorderPrintReportView>({
    queryKey: ["/api/v1/workorders", workOrderId, "print-report"],
    queryFn: async () => {
      const response = await syncroFetch<{ data: WorkorderPrintReportView }>(
        `/api/v1/workorders/${workOrderId}/print-report`,
        { method: "GET" },
      );
      return response.data;
    },
    enabled: Boolean(workOrderId),
    retry: false,
  });
}
