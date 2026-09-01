import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type {
  AddRoleRequest,
  SetJobRequest,
  SystemRoleListResponse,
  UserBindingsView,
} from "@/features/organization/types";
import { syncroFetch } from "@/lib/api/orval-mutator";

export const userBindingsQueryKey = (userId?: string) =>
  ["/api/v1/user-bindings", userId] as const;

export function useGetUserBindings(userId?: string) {
  return useQuery({
    queryKey: userBindingsQueryKey(userId),
    queryFn: () => syncroFetch<{ data: UserBindingsView }>(`/api/v1/user-bindings/${userId}`),
    enabled: Boolean(userId),
  });
}

export function useSetUserJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, data }: { userId: string; data: SetJobRequest }) =>
      syncroFetch<{ data: UserBindingsView }>(`/api/v1/user-bindings/${userId}/job`, {
        method: "PUT",
        body: JSON.stringify(data),
      }),
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: userBindingsQueryKey(vars.userId) });
    },
  });
}

export function useAddUserRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, data }: { userId: string; data: AddRoleRequest }) =>
      syncroFetch<{ data: UserBindingsView }>(`/api/v1/user-bindings/${userId}/roles`, {
        method: "POST",
        body: JSON.stringify(data),
      }),
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: userBindingsQueryKey(vars.userId) });
    },
  });
}

export function useRemoveUserRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, bindingId }: { userId: string; bindingId: string }) =>
      syncroFetch<{ data: undefined }>(
        `/api/v1/user-bindings/${userId}/roles/${bindingId}`,
        { method: "DELETE" },
      ),
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: userBindingsQueryKey(vars.userId) });
    },
  });
}

export const systemRolesQueryKey = () => ["/api/v1/system-roles"] as const;

export function useListSystemRoles() {
  return useQuery({
    queryKey: systemRolesQueryKey(),
    queryFn: () => syncroFetch<{ data: SystemRoleListResponse }>("/api/v1/system-roles"),
  });
}
