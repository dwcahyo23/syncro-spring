"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import type {
  CreateSparepartRequestRequest,
  MreRequest,
  SparepartRequestView,
  TransitionRequest,
} from "@/features/sparepart-requests/types";
import { syncroFetch } from "@/lib/api/orval-mutator";

const REQUESTS_KEY = "/api/v1/sparepart-requests";

/** Creates a sparepart request (story 12-1). */
export function useCreateSparepartRequest() {
  return useMutation({
    mutationFn: async (data: CreateSparepartRequestRequest) => {
      const response = await syncroFetch<{ data: SparepartRequestView }>(REQUESTS_KEY, {
        method: "POST",
        body: JSON.stringify(data),
      });
      return response.data;
    },
    onSuccess: () => {
      toast.success("Sparepart request created");
    },
    onError: () => {
      toast.error("Failed to create sparepart request");
    },
  });
}

/** Transitions a sparepart request (story 12-2, FR-141). */
export function useTransitionRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, data }: { id: string; data: TransitionRequest }) => {
      const response = await syncroFetch<{ data: SparepartRequestView }>(`${REQUESTS_KEY}/${id}/transition`, {
        method: "POST",
        body: JSON.stringify(data),
      });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [REQUESTS_KEY] });
      toast.success("Request status updated");
    },
    onError: (error: unknown) => {
      const err = error as { code?: string };
      if (err.code === "INVALID_STATE_TRANSITION") {
        toast.error("This transition is not allowed in the current state.");
      } else {
        toast.error("Failed to update request status");
      }
    },
  });
}

/** Records a manual MRE code (story 12-2, FR-145). */
export function useRecordMre() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, data }: { id: string; data: MreRequest }) => {
      const response = await syncroFetch<{ data: SparepartRequestView }>(`${REQUESTS_KEY}/${id}/mre`, {
        method: "POST",
        body: JSON.stringify(data),
      });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [REQUESTS_KEY] });
      toast.success("MRE code recorded");
    },
    onError: (error: unknown) => {
      const err = error as { code?: string };
      if (err.code === "INVALID_STATE_TRANSITION") {
        toast.error("MRE can only be recorded for a purchase-requested part.");
      } else {
        toast.error("Failed to record MRE code");
      }
    },
  });
}
