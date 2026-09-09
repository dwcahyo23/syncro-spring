"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import { kpiMaterializedQueryPrefix } from "@/features/analytics/hooks/use-kpi-materialized";
import { apiErrorMessage, errorResponse } from "@/lib/api/error-response";
import { SyncroApiError, syncroFetch } from "@/lib/api/orval-mutator";

/**
 * KPI target configuration contract (story 20-2, reusing the 20-1 endpoints
 * {@code GET/PUT /api/v1/kpi/targets}). Absent from the committed OpenAPI snapshot,
 * so hand-written following the 14-2 dashboard pattern. The PUT is the 20-1 upsert —
 * role-gated server-side (SUPER_ADMIN/MANAGER_MAINTENANCE) and audit-logged there;
 * the frontend only hides the affordance, never enforces it. A successful upsert
 * invalidates the target list and every materialized read so the next read reflects
 * the new verdict (AC3).
 */

export interface KpiTargetView {
  id: string;
  plantId: string;
  plantCode: string | null;
  month: string;
  monthlyBreakdownTarget: number | null;
  mtbfTargetDays: number | null;
  mttrTargetMinutes: number | null;
  oeeQualityPercent: number | null;
  oeePerformancePercent: number | null;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface KpiTargetUpsertRequest {
  plantId: string;
  month: string;
  monthlyBreakdownTarget: number | null;
  mtbfTargetDays: number | null;
  mttrTargetMinutes: number | null;
  oeeQualityPercent: number | null;
  oeePerformancePercent: number | null;
}

export const kpiTargetsQueryPrefix = "/api/v1/kpi/targets";

export function kpiTargetsQueryKey(plantId: string) {
  return [kpiTargetsQueryPrefix, plantId];
}

export function useKpiTargets(plantId: string | undefined, enabled?: boolean) {
  return useQuery<KpiTargetView[]>({
    queryKey: kpiTargetsQueryKey(plantId ?? "none"),
    queryFn: async () => {
      const response = await syncroFetch<{ data: KpiTargetView[] }>(
        `${kpiTargetsQueryPrefix}?plantId=${encodeURIComponent(plantId ?? "")}`,
        { method: "GET" },
      );
      return response.data;
    },
    enabled: (enabled ?? true) && Boolean(plantId),
    staleTime: 60 * 1000,
    retry: false,
  });
}

export function useUpsertKpiTarget() {
  const t = useTranslations("analytics");
  const te = useTranslations("errors");
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (request: KpiTargetUpsertRequest) => {
      const response = await syncroFetch<{ data: KpiTargetView }>(kpiTargetsQueryPrefix, {
        method: "PUT",
        body: JSON.stringify(request),
      });
      return response.data;
    },
    onSuccess: () => {
      // Revalidation: the stored target must show up in the next materialized read
      // (verdict) and in the target list (dialog prefill).
      void queryClient.invalidateQueries({ queryKey: [kpiTargetsQueryPrefix] });
      void queryClient.invalidateQueries({ queryKey: [kpiMaterializedQueryPrefix] });
      toast.success(t("targetUpsert.saved"));
    },
    onError: (error: unknown) => {
      // 403 means "role gate" for this action — branch on the status before the
      // generic errors catalog so the copy stays action-specific (rule 23-2-8).
      if (error instanceof SyncroApiError && error.status === 403) {
        toast.error(t("targetUpsert.forbidden"));
        return;
      }
      toast.error(apiErrorMessage(te, errorResponse(error)));
    },
  });
}
