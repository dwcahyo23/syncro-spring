import type { UseMutationOptions } from "@tanstack/react-query";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { syncroFetch } from "@/lib/api/orval-mutator";

export function useAssignSectionLeader<TError = unknown>(options?: {
  mutation?: UseMutationOptions<unknown, TError, { sectionId: string; userId: string }>;
}) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ sectionId, userId }: { sectionId: string; userId: string }) =>
      syncroFetch<{ data: { sectionId: string; leaderUserId: string } }>(`/api/v1/sections/${sectionId}/leader`, {
        method: "PUT",
        body: JSON.stringify({ userId }),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["/api/v1/sections"] });
    },
    ...options?.mutation,
  });
}

export function useClearSectionLeader<TError = unknown>(options?: {
  mutation?: UseMutationOptions<unknown, TError, string>;
}) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (sectionId: string) =>
      syncroFetch<{ data: undefined }>(`/api/v1/sections/${sectionId}/leader`, { method: "DELETE" }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["/api/v1/sections"] });
    },
    ...options?.mutation,
  });
}
