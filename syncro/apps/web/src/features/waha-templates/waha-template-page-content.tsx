"use client";

import { useState } from "react";
import { toast } from "sonner";

import { Skeleton } from "@/components/ui/skeleton";
import { RoleGuard } from "@/components/syncro/role-guard";
import { WahaTemplateEditor } from "@/components/syncro/waha-template-editor";
import { useGetActiveWahaTemplate, useUpsertWahaTemplate, type upsertWahaTemplateResponse } from "@/lib/api/generated/syncro";
import { useAuthUser } from "@/lib/auth/use-auth-user";
import { SyncroApiError } from "@/lib/api/orval-mutator";

const KNOWN_VARIABLES = [
  "{machineCode}",
  "{machineName}",
  "{plantCode}",
  "{machineGroup}",
  "{sparepartName}",
  "{thresholdPercent}",
  "{currentCount}",
  "{alertTime}",
];

const PREVIEW_SAMPLE: Record<string, string> = {
  machineCode: "BF-08410",
  machineName: "JBF19",
  plantCode: "GM1",
  machineGroup: "Forming",
  sparepartName: "Electric PLC Wecon LX5",
  thresholdPercent: "90",
  currentCount: "9450",
  alertTime: "2026-08-20 08:00",
};

function renderPreview(template: string): string {
  return template.replace(/\{(\w+)\}/g, (_, key) => PREVIEW_SAMPLE[key] ?? `{${key}}`);
}

function WahaTemplateContent({ readOnly }: { readOnly: boolean }) {
  const { data, isLoading, isError, error, refetch } = useGetActiveWahaTemplate({
    query: {
      staleTime: 30_000,
      retry: (failureCount: number, err: unknown) =>
        failureCount < 2 && !(err instanceof SyncroApiError && (err.status === 403 || err.status === 401)),
    },
  });

  const [draftBody, setDraftBody] = useState<string | null>(null);

  const currentBody = draftBody ?? data?.data?.body ?? "";
  const preview = renderPreview(currentBody);

  const upsert = useUpsertWahaTemplate({
    mutation: {
      onSuccess: (response: upsertWahaTemplateResponse) => {
        setDraftBody(response.data?.body ?? null);
        toast.success("Template saved");
      },
      onError: () => {
        toast.error("Failed to save template. Check for unknown variables.");
      },
    },
  });

  const handleSave = () => {
    upsert.mutate({ data: { body: currentBody } });
  };

  if (isLoading) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-6 w-48" />
        <Skeleton className="h-40 w-full" />
        <Skeleton className="h-32 w-full" />
      </div>
    );
  }

  if (isError) {
    if (error instanceof SyncroApiError && error.status === 403) {
      return (
        <div className="rounded-lg border p-6 text-center">
          <h2 className="text-lg font-semibold">Access denied</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            You do not have access to WAHA templates.
          </p>
        </div>
      );
    }
    return (
      <div className="flex flex-col items-center gap-3 rounded-lg border p-6">
        <p className="text-sm text-muted-foreground">Failed to load template.</p>
        <button
          type="button"
          className="rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onClick={() => void refetch()}
        >
          Retry
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold">Alert Notification Template</h2>
        <p className="text-sm text-muted-foreground mt-1">
          This template is used for WhatsApp alert messages sent when a sparepart threshold is reached.
        </p>
      </div>

      <WahaTemplateEditor
        value={currentBody}
        onChange={setDraftBody}
        availableVariables={KNOWN_VARIABLES}
        preview={preview}
        onSave={readOnly ? undefined : handleSave}
        readOnly={readOnly}
        isSaving={upsert.isPending}
      />
    </div>
  );
}

export function WahaTemplatePageContent() {
  const user = useAuthUser();
  const readOnly = user?.applicationRole === "VIEWER";

  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE", "VIEWER"]} title="WAHA Templates">
      <WahaTemplateContent readOnly={readOnly} />
    </RoleGuard>
  );
}
