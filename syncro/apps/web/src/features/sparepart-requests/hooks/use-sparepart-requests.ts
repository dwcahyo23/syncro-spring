"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import type {
  ApproveRequest,
  CompleteRequest,
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

/** Approves a sparepart request (story 12-3, FR-142/AD-16). */
export function useApproveRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, data }: { id: string; data?: ApproveRequest }) => {
      const response = await syncroFetch<{ data: SparepartRequestView }>(`${REQUESTS_KEY}/${id}/approve`, {
        method: "POST",
        body: data ? JSON.stringify(data) : undefined,
      });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [REQUESTS_KEY] });
      toast.success("Request approved");
    },
    onError: (error: unknown) => {
      const err = error as { code?: string };
      if (err.code === "SELF_APPROVAL_FORBIDDEN") {
        toast.error("You cannot approve your own request.");
      } else if (err.code === "INVALID_STATE_TRANSITION") {
        toast.error("This request is no longer pending approval.");
      } else if (err.code === "FORBIDDEN") {
        toast.error("You do not have the required role or scope to approve this request.");
      } else {
        toast.error("Failed to approve request");
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

/** Completes a new-item request (story 12-4, FR-144). */
export function useCompleteRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, data }: { id: string; data: CompleteRequest }) => {
      const response = await syncroFetch<{ data: SparepartRequestView }>(`${REQUESTS_KEY}/${id}/complete`, {
        method: "POST",
        body: JSON.stringify(data),
      });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [REQUESTS_KEY] });
      toast.success("Request completed");
    },
    onError: (error: unknown) => {
      const err = error as { code?: string };
      if (err.code === "INVALID_STATE_TRANSITION") {
        toast.error("This request is no longer pending completion.");
      } else if (err.code === "DUPLICATE_MATERIAL_CODE") {
        toast.error("That material code already belongs to another part.");
      } else {
        toast.error("Failed to complete request");
      }
    },
  });
}
