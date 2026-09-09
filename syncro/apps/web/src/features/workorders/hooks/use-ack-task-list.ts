"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import type { AckTaskList, AckView } from "@/features/workorders/types";
import { syncroFetch } from "@/lib/api/orval-mutator";

const ACK_TASK_LIST_KEY = "/api/v1/workorders/ack-task-list";

/** localStorage key for the single-use AUTO_LOGIN token from the WAHA ack link. */
export const ACK_AUTO_LOGIN_STORAGE_KEY = "syncro:ackAutoLoginToken";

/** The 4-hour ack landing task list (FR-181): acknowledged vs pending, rated vs unrated. */
export function useAckTaskList(enabled = true) {
  return useQuery<AckTaskList>({
    queryKey: [ACK_TASK_LIST_KEY],
    queryFn: async () => {
      const response = await syncroFetch<{ data: AckTaskList }>(ACK_TASK_LIST_KEY, { method: "GET" });
      return response.data;
    },
    enabled,
    staleTime: 15_000,
    retry: false,
  });
}

/** Acknowledges a workorder's 4-hour escalation (stops further escalation). */
export function useAcknowledge() {
  const queryClient = useQueryClient();
  const tm = useTranslations("workOrders");
  return useMutation({
    mutationFn: async (workOrderId: string) => {
      const response = await syncroFetch<{ data: AckView }>(`/api/v1/workorders/${workOrderId}/acknowledge`, {
        method: "POST",
      });
      return response.data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [ACK_TASK_LIST_KEY] });
      toast.success(tm("messages.workorderAcknowledged"));
    },
    onError: () => {
      toast.error(tm("messages.ackFailed"));
    },
  });
}
