import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type {
  CreateDepartmentRequest,
  DepartmentListResponse,
  DepartmentView,
  SetDepartmentMembersRequest,
  UpdateDepartmentRequest,
} from "@/features/organization/types";
import { syncroFetch } from "@/lib/api/orval-mutator";

export const listDepartmentsQueryKey = (plantId?: string, includeInactive?: boolean) =>
  ["/api/v1/departments", plantId, includeInactive] as const;

export function useListDepartments(plantId?: string, includeInactive = false) {
  return useQuery({
    queryKey: listDepartmentsQueryKey(plantId, includeInactive),
    queryFn: () => {
      const params = new URLSearchParams();
      if (plantId) {
        params.set("plantId", plantId);
      }
      params.set("includeInactive", String(includeInactive));
      const qs = params.toString();
      return syncroFetch<{ data: DepartmentListResponse }>(`/api/v1/departments?${qs}`);
    },
    enabled: Boolean(plantId),
  });
}

export function useCreateDepartment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateDepartmentRequest) =>
      syncroFetch<{ data: DepartmentView }>("/api/v1/departments", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["/api/v1/departments"] });
    },
  });
}

export function useUpdateDepartment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ departmentId, data }: { departmentId: string; data: UpdateDepartmentRequest }) =>
      syncroFetch<{ data: DepartmentView }>(`/api/v1/departments/${departmentId}`, {
        method: "PUT",
        body: JSON.stringify(data),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["/api/v1/departments"] });
    },
  });
}

export function useDeleteDepartment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (departmentId: string) =>
      syncroFetch<{ data: undefined }>(`/api/v1/departments/${departmentId}`, { method: "DELETE" }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["/api/v1/departments"] });
    },
  });
}

export function useSetDepartmentMembers() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ departmentId, data }: { departmentId: string; data: SetDepartmentMembersRequest }) =>
      syncroFetch<{ data: DepartmentView }>(`/api/v1/departments/${departmentId}/members`, {
        method: "PUT",
        body: JSON.stringify(data),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["/api/v1/departments"] });
    },
  });
}
