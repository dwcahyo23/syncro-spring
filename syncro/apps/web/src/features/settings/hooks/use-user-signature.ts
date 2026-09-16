"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { API_BASE_URL, SyncroApiError, syncroFetch } from "@/lib/api/orval-mutator";
import { getAuthToken } from "@/lib/auth/auth-client";

/** Mirrors backend AuthDtos.UserSignatureView. */
export interface UserSignatureView {
  id: string;
  userId: string;
  bucket: string;
  objectKey: string;
  contentType: string | null;
  sha256: string | null;
  createdAt: string;
  updatedAt: string;
  presignedUrl: string | null;
}

/** Reads the authenticated user's stored signature (404 → null: none stored yet). */
export function useMySignature() {
  return useQuery<UserSignatureView | null>({
    queryKey: ["/api/v1/auth", "user-signatures", "me"],
    queryFn: async () => {
      try {
        const response = await syncroFetch<{ data: UserSignatureView }>("/api/v1/auth/user-signatures/me", {
          method: "GET",
        });
        return response.data;
      } catch (err) {
        if (err instanceof SyncroApiError && err.status === 404) return null;
        throw err;
      }
    },
    staleTime: 60_000,
    retry: false,
  });
}

/**
 * Stores/replaces the authenticated user's signature image. Multipart parts follow the
 * house convention (contentType + data) plus the required `password` part (bcrypt gate;
 * 3 wrong attempts lock saves for 15 minutes → 423 server-side).
 *
 * ponytail: raw fetch instead of syncroFetch — syncroFetch treats every 401 as an
 * expired session and logs the user out, but a wrong SIGNATURE password legitimately
 * answers 401 INVALID_CREDENTIALS mid-session. Same auth header, no logout side effect.
 */
export function useStoreMySignature() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ blob, password }: { blob: Blob; password: string }) => {
      const formData = new FormData();
      formData.append("contentType", blob.type || "image/png");
      formData.append("password", password);
      formData.append("data", blob, "signature.png");
      const token = getAuthToken();
      const response = await fetch(`${API_BASE_URL}/api/v1/auth/user-signatures`, {
        method: "POST",
        body: formData,
        headers: token ? { Authorization: `Bearer ${token}` } : undefined,
      });
      const payload: unknown = await response.json().catch(() => undefined);
      if (!response.ok) {
        throw new SyncroApiError(response.status, payload);
      }
      return payload as UserSignatureView;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["/api/v1/auth", "user-signatures"] });
    },
  });
}
