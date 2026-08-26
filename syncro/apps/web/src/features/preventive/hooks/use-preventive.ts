"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import type {
  CreatePreventiveProgramRequest,
  PreventiveProgramView,
  PreventiveScheduleView,
} from "@/features/preventive/types";
import { syncroFetch } from "@/lib/api/orval-mutator";

const PROGRAMS_KEY = "/api/v1/preventive-programs";
const SCHEDULES_KEY = "/api/v1/preventive-schedules";

/** Lists preventive programs (scope-filtered). */
export function usePreventivePrograms() {
  return useQuery<PreventiveProgramView[]>({
    queryKey: [PROGRAMS_KEY],
    queryFn: async () => {
      const response = await syncroFetch<{ data: PreventiveProgramView[] }>(PROGRAMS_KEY, { method: "GET" });
      return response.data;
    },
    staleTime: 30_000,
  });
}

/** Lists due/overdue preventive schedules (scope-filtered, server-derived status). */
export function usePreventiveSchedules() {
  return useQuery<PreventiveScheduleView[]>({
    queryKey: [SCHEDULES_KEY],
    queryFn: async () => {
      const response = await syncroFetch<{ data: PreventiveScheduleView[] }>(SCHEDULES_KEY, { method: "GET" });
      return response.data;
    },
    staleTime: 30_000,
    retry: false,
  });
}

/** Creates a preventive program (generates its schedule window). */
export function useCreatePreventiveProgram() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (data: CreatePreventiveProgramRequest) => {
      const response = await syncroFetch<{ data: PreventiveProgramView }>(PROGRAMS_KEY, {
        method: "POST",
        body: JSON.stringify(data),
      });
      return response.data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [PROGRAMS_KEY] });
      void queryClient.invalidateQueries({ queryKey: [SCHEDULES_KEY] });
      toast.success("Preventive program created");
    },
    onError: () => {
      toast.error("Failed to create preventive program");
    },
  });
}

/** Deletes a preventive program (cascades its schedules). */
export function useDeletePreventiveProgram() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (programId: string) => {
      await syncroFetch(`${PROGRAMS_KEY}/${programId}`, { method: "DELETE" });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [PROGRAMS_KEY] });
      void queryClient.invalidateQueries({ queryKey: [SCHEDULES_KEY] });
      toast.success("Preventive program deleted");
    },
    onError: () => {
      toast.error("Failed to delete preventive program");
    },
  });
}
