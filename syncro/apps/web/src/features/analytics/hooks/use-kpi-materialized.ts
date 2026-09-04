"use client";

import { useQuery } from "@tanstack/react-query";

import { syncroFetch } from "@/lib/api/orval-mutator";

/**
 * Materialized monthly KPI contract (story 20-2, reading the 20-1 endpoint
 * {@code GET /api/v1/kpi/materialized/{type}?month=&plantId=}). The endpoint is absent
 * from the committed OpenAPI snapshot, so this is a hand-written hook following the
 * 14-2 dashboard pattern. All computation — including the actual-vs-target verdict —
 * is backend-owned; this hook only renders. Gated on {@code enabled} so no fetch races
 * the plant-scope store or a hidden tab.
 */

export type KpiMaterializedType = "mtbf" | "mttr" | "mar" | "pm-completion" | "technician" | "breakdown";

/** Backend-computed verdict (stable uppercase contract string, story 20-2). */
export type KpiTargetStatus = "ON_TARGET" | "BELOW_TARGET" | "ABOVE_TARGET" | "NO_TARGET" | "INSUFFICIENT_DATA";

export interface KpiMtbfRow {
  plantId: string;
  machineId: string;
  mtbfDays: number | null;
  targetValue: number | null;
  targetStatus: KpiTargetStatus;
}

export interface KpiMttrRow {
  plantId: string;
  wallClockMinutes: number | null;
  actualWorkingMinutes: number | null;
  targetValue: number | null;
  targetStatus: KpiTargetStatus;
}

export interface KpiMarRow {
  plantId: string;
  plannedAvailableMinutes: number;
  downtimeMinutes: number;
  marPercent: number | null;
  sourceStatus: string | null;
  sourceMessage: string | null;
  targetValue: number | null;
  targetStatus: KpiTargetStatus;
}

export interface KpiPmCompletionRow {
  plantId: string;
  completionRate: number | null;
  completedCount: number;
  plannedCount: number;
  sourceStatus: string | null;
  sourceMessage: string | null;
  targetValue: number | null;
  targetStatus: KpiTargetStatus;
}

export interface KpiTechnicianRow {
  plantId: string;
  technicianId: string;
  averageRating: number | null;
  totalWo: number;
  firstTimeFixRate: number | null;
  targetValue: number | null;
  targetStatus: KpiTargetStatus;
}

export interface KpiBreakdownRow {
  plantId: string;
  count: number;
  targetValue: number | null;
  targetStatus: KpiTargetStatus;
}

export interface KpiMaterializedTargetView {
  plantId: string;
  month: string;
  monthlyBreakdownTarget: number | null;
  mtbfTargetDays: number | null;
  mttrTargetMinutes: number | null;
  oeeQualityPercent: number | null;
  oeePerformancePercent: number | null;
}

export interface KpiRefreshView {
  refreshKey: string;
  status: "RUNNING" | "SUCCESS" | "FAILED";
  refreshedAt: string;
  message: string | null;
}

export interface KpiMaterializedResponse {
  type: string;
  month: string;
  status: "AVAILABLE" | "INSUFFICIENT_DATA";
  mtbfRows: KpiMtbfRow[];
  mttrRows: KpiMttrRow[];
  marRows: KpiMarRow[];
  pmCompletionRows: KpiPmCompletionRow[];
  technicianRows: KpiTechnicianRow[];
  breakdownRows: KpiBreakdownRow[];
  target: KpiMaterializedTargetView | null;
  refresh: KpiRefreshView | null;
}

/** Shared query-key prefix so a target mutation can invalidate every materialized read. */
export const kpiMaterializedQueryPrefix = "/api/v1/kpi/materialized";

export function kpiMaterializedQueryKey(type: KpiMaterializedType, month: string, plantId?: string) {
  return [kpiMaterializedQueryPrefix, type, month, plantId ?? "all"];
}

export function useKpiMaterialized(type: KpiMaterializedType, month: string, plantId?: string, enabled?: boolean) {
  const params = new URLSearchParams({ month });
  if (plantId) {
    params.set("plantId", plantId);
  }

  return useQuery<KpiMaterializedResponse>({
    queryKey: kpiMaterializedQueryKey(type, month, plantId),
    queryFn: async () => {
      const response = await syncroFetch<{ data: KpiMaterializedResponse }>(
        `${kpiMaterializedQueryPrefix}/${type}?${params.toString()}`,
        { method: "GET" },
      );
      return response.data;
    },
    enabled: enabled ?? true,
    // Materialized rows only change when the monthly refresh job runs (or a target is
    // upserted, which invalidates this key) — a short client freshness window is enough.
    staleTime: 60 * 1000,
    retry: false,
  });
}
