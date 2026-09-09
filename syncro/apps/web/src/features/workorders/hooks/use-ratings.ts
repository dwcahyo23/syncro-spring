"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import type {
  RateableWorkorderView,
  RateTechnicianRequest,
  RateWorkorderRequest,
  RatingDimensionView,
  RatingView,
} from "@/features/workorders/types";
import { syncroFetch } from "@/lib/api/orval-mutator";

const RATINGS_PAGE_KEY = "/api/v1/workorders/ratings";

/** Lists CLOSED workorders the current user can rate (FR-121/FR-124). */
export function useRateableWorkorders() {
  return useQuery<RateableWorkorderView[]>({
    queryKey: [RATINGS_PAGE_KEY],
    queryFn: async () => {
      const response = await syncroFetch<{ data: RateableWorkorderView[] }>(RATINGS_PAGE_KEY, { method: "GET" });
      return response.data;
    },
    staleTime: 30_000,
    retry: false,
  });
}

/** Lists the configured rating dimensions (shared config, AD-14). */
export function useRatingDimensions() {
  return useQuery<RatingDimensionView[]>({
    queryKey: ["/api/v1/rating-dimensions"],
    queryFn: async () => {
      const response = await syncroFetch<{ data: RatingDimensionView[] }>("/api/v1/rating-dimensions", {
        method: "GET",
      });
      return response.data;
    },
    staleTime: 60_000,
  });
}

/** Lists a workorder's ratings with per-dimension scores. */
export function useWorkorderRatings(workorderId: string) {
  return useQuery<RatingView[]>({
    queryKey: ["ratings", workorderId],
    queryFn: async () => {
      const response = await syncroFetch<{ data: RatingView[] }>(`/api/v1/workorders/${workorderId}/ratings`, {
        method: "GET",
      });
      return response.data;
    },
    enabled: Boolean(workorderId),
    staleTime: 10_000,
  });
}

/** Invalidates the ratings page and the workorder's rating list. */
function invalidateRatingQueries(queryClient: ReturnType<typeof useQueryClient>, workorderId: string) {
  void queryClient.invalidateQueries({ queryKey: [RATINGS_PAGE_KEY] });
  void queryClient.invalidateQueries({ queryKey: ["ratings", workorderId] });
}

/** Rates a technician who executed a closed workorder (in-scope section leader, FR-121). */
export function useRateTechnician(workorderId: string) {
  const queryClient = useQueryClient();
  const tm = useTranslations("workOrders");
  return useMutation({
    mutationFn: async (data: RateTechnicianRequest) => {
      const response = await syncroFetch<{ data: RatingView }>(`/api/v1/workorders/${workorderId}/ratings/technician`, {
        method: "POST",
        body: JSON.stringify(data),
      });
      return response.data;
    },
    onSuccess: () => {
      invalidateRatingQueries(queryClient, workorderId);
      toast.success(tm("messages.technicianRatingSubmitted"));
    },
    onError: () => {
      toast.error(tm("messages.technicianRatingFailed"));
    },
  });
}

/** Rates a closed maintenance workorder (PRODUCTION_LEADER with plant access, FR-124). */
export function useRateWorkorder(workorderId: string) {
  const queryClient = useQueryClient();
  const tm = useTranslations("workOrders");
  return useMutation({
    mutationFn: async (data: RateWorkorderRequest) => {
      const response = await syncroFetch<{ data: RatingView }>(`/api/v1/workorders/${workorderId}/ratings/workorder`, {
        method: "POST",
        body: JSON.stringify(data),
      });
      return response.data;
    },
    onSuccess: () => {
      invalidateRatingQueries(queryClient, workorderId);
      toast.success(tm("messages.workorderRatingSubmitted"));
    },
    onError: () => {
      toast.error(tm("messages.workorderRatingFailed"));
    },
  });
}
