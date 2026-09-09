"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import type {
  AdjustSparepartStockRequest,
  CreateSparepartStockRequest,
  SparepartStockView,
} from "@/features/sparepart-stock/types";
import { apiErrorMessage, errorResponse } from "@/lib/api/error-response";
import { syncroFetch } from "@/lib/api/orval-mutator";

const STOCK_KEY = "/api/v1/sparepart-stock";

/** Lists stock rows for a plant (story 12-4, FR-146). */
export function useListStock(plantId: string | null) {
  return useQuery<SparepartStockView[]>({
    queryKey: [STOCK_KEY, plantId],
    queryFn: async () => {
      if (!plantId) return [];
      const params = new URLSearchParams();
      params.set("plantId", plantId);
      const res = await syncroFetch<{ data: { items: SparepartStockView[] } }>(`${STOCK_KEY}?${params.toString()}`, {
        method: "GET",
      });
      return res.data?.items ?? [];
    },
    enabled: !!plantId,
    staleTime: 30_000,
  });
}

/** Creates (or upserts) a stock row (story 12-4, FR-146). */
export function useCreateStock() {
  const queryClient = useQueryClient();
  const t = useTranslations("stock");
  const te = useTranslations("errors");
  return useMutation({
    mutationFn: async (data: CreateSparepartStockRequest) => {
      const response = await syncroFetch<{ data: SparepartStockView }>(STOCK_KEY, {
        method: "POST",
        body: JSON.stringify(data),
      });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [STOCK_KEY] });
      toast.success(t("toast.created"));
    },
    onError: (error: unknown) => {
      const resp = errorResponse(error);
      toast.error(resp ? apiErrorMessage(te, resp) : t("toast.createFailed"));
    },
  });
}

/** Adjusts stock with a signed delta (story 12-4, FR-146). */
export function useAdjustStock() {
  const queryClient = useQueryClient();
  const t = useTranslations("stock");
  const te = useTranslations("errors");
  return useMutation({
    mutationFn: async ({ materialCode, data }: { materialCode: string; data: AdjustSparepartStockRequest }) => {
      const response = await syncroFetch<{ data: SparepartStockView }>(
        `${STOCK_KEY}/${encodeURIComponent(materialCode)}/adjust`,
        {
          method: "POST",
          body: JSON.stringify(data),
        },
      );
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [STOCK_KEY] });
      toast.success(t("toast.adjusted"));
    },
    onError: (error: unknown) => {
      const resp = errorResponse(error);
      if (resp?.code === "NEGATIVE_STOCK_REJECTED") {
        toast.error(te("NEGATIVE_STOCK_REJECTED"));
      } else {
        toast.error(resp ? apiErrorMessage(te, resp) : t("toast.adjustFailed"));
      }
    },
  });
}

/** Fetches reorder warnings for a plant (story 12-4, FR-146). */
export function useReorderWarnings(plantId: string | null) {
  return useQuery<SparepartStockView[]>({
    queryKey: [STOCK_KEY, "reorder-warnings", plantId],
    queryFn: async () => {
      if (!plantId) return [];
      const params = new URLSearchParams();
      params.set("plantId", plantId);
      const res = await syncroFetch<{ data: { items: SparepartStockView[] } }>(
        `${STOCK_KEY}/reorder-warnings?${params.toString()}`,
        { method: "GET" },
      );
      return res.data?.items ?? [];
    },
    enabled: !!plantId,
    staleTime: 30_000,
  });
}
