"use client";

import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";

import { syncroFetch } from "@/lib/api/orval-mutator";

interface WorkorderAttachmentView {
  id: string;
  workOrderId: string;
  filename: string;
  contentType: string;
  objectKey: string;
  sizeBytes: number;
  uploadedBy: string;
  createdAt: string;
  updatedAt: string | null;
  presignedUrl: string;
}

/**
 * Uploads a signature image to Garage via the workorder-attachment endpoint (story 14-3,
 * FR-175). The returned object key is auto-filled into the approve form — one cohesive
 * sign step, matching the preventive 11-2 pattern.
 */
export function useWorkorderSignatureUpload(workOrderId: string) {
  return useMutation({
    mutationFn: async (file: File) => {
      const formData = new FormData();
      formData.append("filename", file.name);
      formData.append("contentType", file.type);
      formData.append("data", file);
      const response = await syncroFetch<{ data: WorkorderAttachmentView }>(
        `/api/v1/workorders/${workOrderId}/attachments`,
        { method: "POST", body: formData, headers: {} },
      );
      return response.data;
    },
    onError: () => {
      toast.error("Failed to upload signature image");
    },
  });
}
