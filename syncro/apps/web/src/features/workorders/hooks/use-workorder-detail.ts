"use client";

import { useQuery } from "@tanstack/react-query";

import type { WorkAssignmentView, WorkLogView, WorkOrderListRow, WorkOrderPage } from "@/features/workorders/types";
import { syncroFetch } from "@/lib/api/orval-mutator";

/**
 * Detail-page reads for a single workorder (QA 2026-09-14): assignments and work logs.
 * The header/parts/narrative aggregate comes from useWorkorderPrintReport — the backend
 * has no GET /{id}, and print-report is the composed read that already exists.
 * Query keys are shared with workorder-actions-cell so assign/log mutations refresh both.
 */
export function useWorkOrderAssignments(workOrderId: string | null) {
  return useQuery<WorkAssignmentView[]>({
    queryKey: ["/api/v1/workorders", workOrderId, "assignments"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: WorkAssignmentView[] }>(`/api/v1/workorders/${workOrderId}/assignments`, {
        method: "GET",
      });
      return res.data;
    },
    enabled: Boolean(workOrderId),
    retry: false,
  });
}

export function useWorkOrderWorkLogs(workOrderId: string | null) {
  return useQuery<WorkLogView[]>({
    queryKey: ["/api/v1/workorders", workOrderId, "work-logs"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: WorkLogView[] }>(`/api/v1/workorders/${workOrderId}/work-logs`, {
        method: "GET",
      });
      return res.data;
    },
    enabled: Boolean(workOrderId),
    retry: false,
  });
}

/**
 * The print-report header's createdAt is a date-only string (midnight), too coarse to
 * clamp work-log backdating — a WO created at 13:23 must reject a 08:00 session. The
 * list-row createdAt carries the full Instant, so the detail page fetches the single
 * row via the search endpoint to get the exact creation timestamp.
 */
export function useWorkOrderCreatedInstant(workOrderId: string | null) {
  return useQuery<string | null>({
    queryKey: ["/api/v1/workorders", workOrderId, "created-instant"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: WorkOrderPage }>(
        `/api/v1/workorders?search=${encodeURIComponent(workOrderId ?? "")}&page=0&size=5`,
        { method: "GET" },
      );
      const row = (res.data.items ?? []).find((r: WorkOrderListRow) => r.id === workOrderId);
      return row?.createdAt ?? null;
    },
    enabled: Boolean(workOrderId),
    staleTime: 60_000,
    retry: false,
  });
}
