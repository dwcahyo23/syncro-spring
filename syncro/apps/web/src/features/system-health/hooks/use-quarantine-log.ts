import { useQuery } from "@tanstack/react-query";

import { API_BASE_URL } from "@/lib/api/orval-mutator";
import { getAuthToken } from "@/lib/auth/auth-client";

export const QUARANTINE_REFRESH_INTERVAL_MS = 30_000;

export type QuarantineEntryView = {
  id: string;
  traceId: string;
  topic: string;
  rawPayload: string;
  rejectionReason: string;
  rejectionField: string | null;
  receivedAt: string;
};

export type QuarantinePage = {
  content: QuarantineEntryView[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
};

async function fetchQuarantineLog(page: number, size: number): Promise<QuarantinePage> {
  const token = getAuthToken();
  const headers = new Headers();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const url = `${API_BASE_URL}/api/v1/telemetry/quarantine?page=${page}&size=${size}`;
  const response = await fetch(url, { headers });
  if (!response.ok) {
    throw new Error(`Quarantine fetch failed: ${response.status}`);
  }
  return response.json() as Promise<QuarantinePage>;
}

export function useQuarantineLog(page: number, size: number) {
  return useQuery({
    queryKey: ["quarantine-log", page, size] as const,
    queryFn: () => fetchQuarantineLog(page, size),
    refetchInterval: QUARANTINE_REFRESH_INTERVAL_MS,
    retry: 2,
  });
}
