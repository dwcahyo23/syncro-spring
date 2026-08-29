"use client";

import { useQuery } from "@tanstack/react-query";

import type { CompanyLogoView } from "@/features/workorders/print-types";
import { syncroFetch } from "@/lib/api/orval-mutator";

/**
 * Reads the company logo (null presignedUrl when not configured — graceful fallback).
 * Shared across print pages (story 14-3, FR-175).
 */
export function useCompanyLogo() {
  return useQuery<CompanyLogoView>({
    queryKey: ["/api/v1/settings", "logo"],
    queryFn: async () => {
      const response = await syncroFetch<{ data: CompanyLogoView }>("/api/v1/settings/logo", { method: "GET" });
      return response.data;
    },
    staleTime: 60_000,
    retry: false,
  });
}
