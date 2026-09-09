"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import type {
  ApproveScheduleRequest,
  ChecklistResultView,
  ChecklistView,
  CreatePreventiveProgramRequest,
  PreventiveAttachmentView,
  PreventiveProgramView,
  PreventiveReportView,
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
  const t = useTranslations("preventive");
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
      toast.success(t("messages.programCreated"));
    },
    onError: () => {
      toast.error(t("messages.programCreateFailed"));
    },
  });
}

/** Deletes a preventive program (cascades its schedules). */
export function useDeletePreventiveProgram() {
  const t = useTranslations("preventive");
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (programId: string) => {
      await syncroFetch(`${PROGRAMS_KEY}/${programId}`, { method: "DELETE" });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [PROGRAMS_KEY] });
      void queryClient.invalidateQueries({ queryKey: [SCHEDULES_KEY] });
      toast.success(t("messages.programDeleted"));
    },
    onError: () => {
      toast.error(t("messages.programDeleteFailed"));
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
  const t = useTranslations("preventive");
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
      toast.success(t("messages.checklistSubmitted"));
    },
    onError: () => {
      toast.error(t("messages.checklistSubmitFailed"));
    },
  });
}

/** Approves a checklist (leader only). */
export function useApproveSchedule() {
  const t = useTranslations("preventive");
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
      toast.success(t("messages.scheduleApproved"));
    },
    onError: () => {
      toast.error(t("messages.scheduleApproveFailed"));
    },
  });
}

/** Skips a schedule (leader only). */
export function useSkipSchedule() {
  const t = useTranslations("preventive");
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (scheduleId: string) => {
      await syncroFetch(`${SCHEDULES_KEY}/${scheduleId}/skip`, { method: "POST" });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [SCHEDULES_KEY] });
      toast.success(t("messages.scheduleSkipped"));
    },
    onError: () => {
      toast.error(t("messages.scheduleSkipFailed"));
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
  const t = useTranslations("preventive");
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
      toast.success(t("messages.evidenceUploaded"));
    },
    onError: () => {
      toast.error(t("messages.evidenceUploadFailed"));
    },
  });
}

/** Deletes evidence. */
export function useDeleteEvidence() {
  const t = useTranslations("preventive");
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ scheduleId, attachmentId }: { scheduleId: string; attachmentId: string }) => {
      await syncroFetch(`${SCHEDULES_KEY}/${scheduleId}/evidence/${attachmentId}`, { method: "DELETE" });
    },
    onSuccess: (_data, variables) => {
      void queryClient.invalidateQueries({ queryKey: [SCHEDULES_KEY, variables.scheduleId, "evidence"] });
      void queryClient.invalidateQueries({ queryKey: [SCHEDULES_KEY] });
      toast.success(t("messages.evidenceDeleted"));
    },
    onError: () => {
      toast.error(t("messages.evidenceDeleteFailed"));
    },
  });
}

/** Reads the preventive report for a schedule (browser-print data). */
export function usePreventiveReport(scheduleId: string | null) {
  return useQuery<PreventiveReportView>({
    queryKey: [SCHEDULES_KEY, scheduleId, "report"],
    queryFn: async () => {
      const response = await syncroFetch<{ data: PreventiveReportView }>(`${SCHEDULES_KEY}/${scheduleId}/report`, {
        method: "GET",
      });
      return response.data;
    },
    enabled: !!scheduleId,
    retry: false,
  });
}
