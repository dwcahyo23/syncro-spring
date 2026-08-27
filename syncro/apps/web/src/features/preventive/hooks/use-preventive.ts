"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import type {
  ApproveScheduleRequest,
  ChecklistResultView,
  ChecklistView,
  CreatePreventiveProgramRequest,
  PreventiveAttachmentView,
  PreventiveProgramView,
  PreventiveScheduleView,
  SubmitChecklistRequest,
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

/** Reads checklist result + items + status for a schedule. */
export function useChecklist(scheduleId: string | null) {
  return useQuery<ChecklistView>({
    queryKey: [SCHEDULES_KEY, scheduleId, "checklist"],
    queryFn: async () => {
      const response = await syncroFetch<{ data: ChecklistView }>(`${SCHEDULES_KEY}/${scheduleId}/checklist`, {
        method: "GET",
      });
      return response.data;
    },
    enabled: !!scheduleId,
    retry: false,
  });
}

/** Submits or amends a checklist. */
export function useSubmitChecklist() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      scheduleId,
      data,
      isAmend,
    }: {
      scheduleId: string;
      data: SubmitChecklistRequest;
      isAmend: boolean;
    }) => {
      const response = await syncroFetch<{ data: ChecklistResultView }>(`${SCHEDULES_KEY}/${scheduleId}/checklist`, {
        method: isAmend ? "PUT" : "POST",
        body: JSON.stringify(data),
      });
      return response.data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [SCHEDULES_KEY] });
      toast.success("Checklist submitted");
    },
    onError: () => {
      toast.error("Failed to submit checklist");
    },
  });
}

/** Approves a checklist (leader only). */
export function useApproveSchedule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ scheduleId, data }: { scheduleId: string; data: ApproveScheduleRequest }) => {
      const response = await syncroFetch<{ data: ChecklistResultView }>(`${SCHEDULES_KEY}/${scheduleId}/approve`, {
        method: "POST",
        body: JSON.stringify(data),
      });
      return response.data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [SCHEDULES_KEY] });
      toast.success("Schedule approved");
    },
    onError: () => {
      toast.error("Failed to approve schedule");
    },
  });
}

/** Skips a schedule (leader only). */
export function useSkipSchedule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (scheduleId: string) => {
      await syncroFetch(`${SCHEDULES_KEY}/${scheduleId}/skip`, { method: "POST" });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [SCHEDULES_KEY] });
      toast.success("Schedule skipped");
    },
    onError: () => {
      toast.error("Failed to skip schedule");
    },
  });
}

/** Lists evidence for a schedule. */
export function useEvidenceList(scheduleId: string | null) {
  return useQuery<PreventiveAttachmentView[]>({
    queryKey: [SCHEDULES_KEY, scheduleId, "evidence"],
    queryFn: async () => {
      const response = await syncroFetch<{ data: PreventiveAttachmentView[] }>(
        `${SCHEDULES_KEY}/${scheduleId}/evidence`,
        { method: "GET" },
      );
      return response.data;
    },
    enabled: !!scheduleId,
    retry: false,
  });
}

/** Uploads evidence for a schedule. */
export function useUploadEvidence() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ scheduleId, file }: { scheduleId: string; file: File }) => {
      const formData = new FormData();
      formData.append("file", file);
      const response = await syncroFetch<{ data: PreventiveAttachmentView }>(
        `${SCHEDULES_KEY}/${scheduleId}/evidence`,
        {
          method: "POST",
          body: formData,
          headers: {},
        },
      );
      return response.data;
    },
    onSuccess: (_data, variables) => {
      void queryClient.invalidateQueries({ queryKey: [SCHEDULES_KEY, variables.scheduleId, "evidence"] });
      void queryClient.invalidateQueries({ queryKey: [SCHEDULES_KEY] });
      toast.success("Evidence uploaded");
    },
    onError: () => {
      toast.error("Failed to upload evidence");
    },
  });
}

/** Deletes evidence. */
export function useDeleteEvidence() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ scheduleId, attachmentId }: { scheduleId: string; attachmentId: string }) => {
      await syncroFetch(`${SCHEDULES_KEY}/${scheduleId}/evidence/${attachmentId}`, { method: "DELETE" });
    },
    onSuccess: (_data, variables) => {
      void queryClient.invalidateQueries({ queryKey: [SCHEDULES_KEY, variables.scheduleId, "evidence"] });
      void queryClient.invalidateQueries({ queryKey: [SCHEDULES_KEY] });
      toast.success("Evidence deleted");
    },
    onError: () => {
      toast.error("Failed to delete evidence");
    },
  });
}
