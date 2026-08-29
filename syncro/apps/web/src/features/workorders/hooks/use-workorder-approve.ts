"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import type { ApproveWorkorderRequest, WorkorderSignatureView } from "@/features/workorders/print-types";
import { syncroFetch } from "@/lib/api/orval-mutator";

/**
 * Approves a DONE/CLOSED workorder with a signature image (story 14-3, FR-175).
 * Gate: in-scope leader/SPV. Duplicate approve surfaces as a 409 from the backend.
 */
export function useWorkorderApprove(workOrderId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (data: ApproveWorkorderRequest) => {
      const response = await syncroFetch<{ data: WorkorderSignatureView }>(
        `/api/v1/workorders/${workOrderId}/approve`,
        { method: "POST", body: JSON.stringify(data) },
      );
      return response.data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders", workOrderId, "print-report"] });
      toast.success("Workorder approved");
    },
    onError: () => {
      toast.error("Failed to approve workorder");
    },
  });
}
