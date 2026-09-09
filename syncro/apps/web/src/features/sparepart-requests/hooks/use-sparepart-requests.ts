"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import type {
  ApproveRequest,
  CompleteRequest,
  CreateSparepartRequestRequest,
  MreRequest,
  SparepartRequestView,
  TransitionRequest,
} from "@/features/sparepart-requests/types";
import { apiErrorMessage, errorResponse } from "@/lib/api/error-response";
import { syncroFetch } from "@/lib/api/orval-mutator";

const REQUESTS_KEY = "/api/v1/sparepart-requests";

/** Creates a sparepart request (story 12-1). */
export function useCreateSparepartRequest() {
  const t = useTranslations("sparepartRequests");
  const te = useTranslations("errors");
  return useMutation({
    mutationFn: async (data: CreateSparepartRequestRequest) => {
      const response = await syncroFetch<{ data: SparepartRequestView }>(REQUESTS_KEY, {
        method: "POST",
        body: JSON.stringify(data),
      });
      return response.data;
    },
    onSuccess: () => {
      toast.success(t("toast.created"));
    },
    onError: (error: unknown) => {
      const resp = errorResponse(error);
      toast.error(resp ? apiErrorMessage(te, resp) : t("toast.createFailed"));
    },
  });
}

/** Transitions a sparepart request (story 12-2, FR-141). */
export function useTransitionRequest() {
  const queryClient = useQueryClient();
  const t = useTranslations("sparepartRequests");
  const te = useTranslations("errors");
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
      toast.success(t("toast.statusUpdated"));
    },
    onError: (error: unknown) => {
      const resp = errorResponse(error);
      if (resp?.code === "INVALID_STATE_TRANSITION") {
        toast.error(t("transition.invalid"));
      } else {
        toast.error(resp ? apiErrorMessage(te, resp) : t("toast.statusFailed"));
      }
    },
  });
}

/** Approves a sparepart request (story 12-3, FR-142/AD-16). */
export function useApproveRequest() {
  const queryClient = useQueryClient();
  const t = useTranslations("sparepartRequests");
  const te = useTranslations("errors");
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
      toast.success(t("toast.approved"));
    },
    onError: (error: unknown) => {
      const resp = errorResponse(error);
      // Action-specific meanings for shared codes branch first (rule 23-2-8);
      // SELF_APPROVAL_FORBIDDEN resolves through the errors catalog directly.
      if (resp?.code === "INVALID_STATE_TRANSITION") {
        toast.error(t("approve.invalidTransition"));
      } else if (resp?.code === "FORBIDDEN") {
        toast.error(t("approve.forbidden"));
      } else {
        toast.error(resp ? apiErrorMessage(te, resp) : t("toast.approveFailed"));
      }
    },
  });
}

/** Records a manual MRE code (story 12-2, FR-145). */
export function useRecordMre() {
  const queryClient = useQueryClient();
  const t = useTranslations("sparepartRequests");
  const te = useTranslations("errors");
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
      toast.success(t("toast.mreRecorded"));
    },
    onError: (error: unknown) => {
      const resp = errorResponse(error);
      if (resp?.code === "INVALID_STATE_TRANSITION") {
        toast.error(t("mre.invalidTransition"));
      } else {
        toast.error(resp ? apiErrorMessage(te, resp) : t("toast.mreFailed"));
      }
    },
  });
}

/** Completes a new-item request (story 12-4, FR-144). */
export function useCompleteRequest() {
  const queryClient = useQueryClient();
  const t = useTranslations("sparepartRequests");
  const te = useTranslations("errors");
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
      toast.success(t("toast.completed"));
    },
    onError: (error: unknown) => {
      const resp = errorResponse(error);
      if (resp?.code === "INVALID_STATE_TRANSITION") {
        toast.error(t("complete.invalidTransition"));
      } else if (resp?.code === "DUPLICATE_MATERIAL_CODE") {
        toast.error(te("DUPLICATE_MATERIAL_CODE"));
      } else {
        toast.error(resp ? apiErrorMessage(te, resp) : t("toast.completeFailed"));
      }
    },
  });
}
