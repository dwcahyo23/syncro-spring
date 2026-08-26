"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import type { AssignTodoRequest, CreateTodoRequest, ReorderTodoRequest, TodoView } from "@/features/workorders/types";
import { syncroFetch } from "@/lib/api/orval-mutator";

/** Lists todos for a workorder. */
export function useTodos(workorderId: string) {
  return useQuery<TodoView[]>({
    queryKey: ["todos", workorderId],
    queryFn: async () => {
      const response = await syncroFetch<{ data: TodoView[] }>(`/api/v1/workorders/${workorderId}/todos`, {
        method: "GET",
      });
      return response.data;
    },
    enabled: Boolean(workorderId),
    staleTime: 10_000,
  });
}

const KANBAN_KEY = ["/api/v1/workorders/kanban"];

/** Invalidates the todos list and the kanban board (todos are embedded in kanban items). */
function invalidateTodoQueries(queryClient: ReturnType<typeof useQueryClient>, workorderId: string) {
  void queryClient.invalidateQueries({ queryKey: ["todos", workorderId] });
  void queryClient.invalidateQueries({ queryKey: KANBAN_KEY });
}

/** Creates a todo on a workorder. */
export function useCreateTodo(workorderId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (data: CreateTodoRequest) => {
      const response = await syncroFetch<{ data: TodoView }>(`/api/v1/workorders/${workorderId}/todos`, {
        method: "POST",
        body: JSON.stringify(data),
      });
      return response.data;
    },
    onSuccess: () => {
      invalidateTodoQueries(queryClient, workorderId);
      toast.success("Todo created");
    },
    onError: () => {
      toast.error("Failed to create todo");
    },
  });
}

/** Assigns a todo to a technician. */
export function useAssignTodo(workorderId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ todoId, data }: { todoId: string; data: AssignTodoRequest }) => {
      const response = await syncroFetch<{ data: TodoView }>(
        `/api/v1/workorders/${workorderId}/todos/${todoId}/assign`,
        {
          method: "PUT",
          body: JSON.stringify(data),
        },
      );
      return response.data;
    },
    onSuccess: () => {
      invalidateTodoQueries(queryClient, workorderId);
      toast.success("Todo assigned");
    },
    onError: () => {
      toast.error("Failed to assign todo");
    },
  });
}

/** Marks a todo complete. */
export function useCompleteTodo(workorderId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (todoId: string) => {
      const response = await syncroFetch<{ data: TodoView }>(
        `/api/v1/workorders/${workorderId}/todos/${todoId}/complete`,
        {
          method: "PUT",
        },
      );
      return response.data;
    },
    onSuccess: () => {
      invalidateTodoQueries(queryClient, workorderId);
      toast.success("Todo completed");
    },
    onError: () => {
      toast.error("Failed to complete todo");
    },
  });
}

/** Reorders a todo within its workorder. */
export function useReorderTodo(workorderId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ todoId, data }: { todoId: string; data: ReorderTodoRequest }) => {
      const response = await syncroFetch<{ data: TodoView }>(
        `/api/v1/workorders/${workorderId}/todos/${todoId}/reorder`,
        {
          method: "PUT",
          body: JSON.stringify(data),
        },
      );
      return response.data;
    },
    onSuccess: () => {
      invalidateTodoQueries(queryClient, workorderId);
    },
    onError: () => {
      toast.error("Failed to reorder todo");
    },
  });
}

/** Deletes a todo. */
export function useDeleteTodo(workorderId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (todoId: string) => {
      await syncroFetch(`/api/v1/workorders/${workorderId}/todos/${todoId}`, { method: "DELETE" });
    },
    onSuccess: () => {
      invalidateTodoQueries(queryClient, workorderId);
      toast.success("Todo deleted");
    },
    onError: () => {
      toast.error("Failed to delete todo");
    },
  });
}
