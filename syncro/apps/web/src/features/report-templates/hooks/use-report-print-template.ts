"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import { SyncroApiError, syncroFetch } from "@/lib/api/orval-mutator";
import type { ReportPrintTemplateType } from "@/lib/print-template";

/**
 * Print-template layout contract (spec "Template Laporan Cetak"),
 * `GET/PUT /api/v1/report-templates/{type}`. Hand-written fetch like the kpi
 * targets precedent (20-2): these endpoints are absent from the committed
 * OpenAPI snapshot, so the generated client lags until the next orval
 * regeneration (DW note: regenerate via `npm run generate:api` when the
 * snapshot refreshes).
 */

export interface ReportPrintTemplateView {
  id: string;
  reportType: ReportPrintTemplateType;
  bodyHtml: string;
  updatedBy: string | null;
  updatedAt: string;
}

export interface UpsertReportPrintTemplateRequest {
  bodyHtml: string;
}

const BASE_KEY = "/api/v1/report-templates";

export function reportPrintTemplateQueryKey(type: ReportPrintTemplateType) {
  return [BASE_KEY, type] as const;
}

/**
 * Reads the stored layout for one report type. A 404 means "no template yet" and
 * resolves to null so the print pages fall back to the built-in layout silently —
 * the missing template is an expected state, never an error toast.
 */
export function useReportPrintTemplate(type: ReportPrintTemplateType | null) {
  return useQuery<ReportPrintTemplateView | null>({
    queryKey: reportPrintTemplateQueryKey(type ?? "WORK_ORDER"),
    queryFn: async () => {
      try {
        const response = await syncroFetch<{ data: ReportPrintTemplateView }>(`${BASE_KEY}/${type}`, {
          method: "GET",
        });
        return response.data;
      } catch (error) {
        if (error instanceof SyncroApiError && error.status === 404) {
          return null;
        }
        throw error;
      }
    },
    enabled: Boolean(type),
    staleTime: 30_000,
    retry: false,
  });
}

/** Saves the layout for one report type (server-side gated to SUPER_ADMIN / MANAGER_MAINTENANCE). */
export function useUpsertReportPrintTemplate(type: ReportPrintTemplateType) {
  const t = useTranslations("reportTemplates");
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (data: UpsertReportPrintTemplateRequest) => {
      const response = await syncroFetch<{ data: ReportPrintTemplateView }>(`${BASE_KEY}/${type}`, {
        method: "PUT",
        body: JSON.stringify(data),
      });
      return response.data;
    },
    onSuccess: (template) => {
      // Revalidate this type's query so reload shows the stored formatting verbatim,
      // and prime the print pages' cache with the same value.
      queryClient.setQueryData(reportPrintTemplateQueryKey(type), template);
      toast.success(t("saved"));
    },
    onError: (error: unknown) => {
      if (error instanceof SyncroApiError && error.status === 403) {
        toast.error(t("saveForbidden"));
        return;
      }
      toast.error(t("saveFailed"));
    },
  });
}
