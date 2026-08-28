"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import type {
  AdjustSparepartStockRequest,
  CreateSparepartStockRequest,
  SparepartStockView,
} from "@/features/sparepart-stock/types";
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
      toast.success("Stock row created");
    },
    onError: () => {
      toast.error("Failed to create stock row");
    },
  });
}

/** Adjusts stock with a signed delta (story 12-4, FR-146). */
export function useAdjustStock() {
  const queryClient = useQueryClient();
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
      toast.success("Stock adjusted");
    },
    onError: (error: unknown) => {
      const err = error as { code?: string };
      if (err.code === "NEGATIVE_STOCK_REJECTED") {
        toast.error("Stock cannot go negative.");
      } else {
        toast.error("Failed to adjust stock");
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
