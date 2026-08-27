import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type { JobTitleListResponse, UpdateUserMasterRequest, UserMasterView } from "@/features/organization/types";
import { syncroFetch } from "@/lib/api/orval-mutator";

export const listUsersQueryKey = () => ["/api/v1/auth/users"] as const;

export function useListUsersMaster() {
  return useQuery({
    queryKey: listUsersQueryKey(),
    queryFn: () => syncroFetch<{ data: UserMasterView[] }>("/api/v1/auth/users"),
  });
}

export function useUpdateUserMaster() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, data }: { userId: string; data: UpdateUserMasterRequest }) =>
      syncroFetch<{ data: UserMasterView }>(`/api/v1/auth/users/${userId}`, {
        method: "PUT",
        body: JSON.stringify(data),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: listUsersQueryKey() });
    },
  });
}

export const listJobTitlesQueryKey = () => ["/api/v1/job-titles"] as const;

export function useListJobTitles() {
  return useQuery({
    queryKey: listJobTitlesQueryKey(),
    queryFn: () => syncroFetch<{ data: JobTitleListResponse }>("/api/v1/job-titles"),
  });
}
