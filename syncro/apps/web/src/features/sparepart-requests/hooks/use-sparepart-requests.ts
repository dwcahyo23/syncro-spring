"use client";

import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";

import type { CreateSparepartRequestRequest, SparepartRequestView } from "@/features/sparepart-requests/types";
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
