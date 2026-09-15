"use client";

import { useState } from "react";

import { TriangleAlert } from "lucide-react";
import { useTranslations } from "next-intl";

import { RichTextEditor } from "@/components/syncro/rich-text-editor";
import { RoleGuard } from "@/components/syncro/role-guard";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  useReportPrintTemplate,
  useUpsertReportPrintTemplate,
} from "@/features/report-templates/hooks/use-report-print-template";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { isLayoutEmpty, type ReportPrintTemplateType } from "@/lib/print-template";

/**
 * Template Laporan page (spec "Template Laporan Cetak"): one global print layout
 * per report type, edited once here and used by every WO / Preventive print page.
 * Two independent tabs — Work Order and Preventive — each loading and saving its
 * own row via PUT /api/v1/report-templates/{type}. A tab's editor remounts (React
 * key) when its loaded template changes, so stored HTML always seeds the content.
 * Editing is offered to SUPER_ADMIN / MANAGER_MAINTENANCE only (mirrors the
 * backend PUT gate; RoleGuard is UX, the server is the real gate).
 */

interface TabCopy {
  description: string;
  emptyHint: string;
  clear: string;
  reload: string;
  loadFailed: string;
  accessDenied: string;
}

function TemplateTab({ type, copy }: { type: ReportPrintTemplateType; copy: TabCopy }) {
  const { data: template, isLoading, isError, error, refetch } = useReportPrintTemplate(type);
  const upsert = useUpsertReportPrintTemplate(type);

  const storedBody = template?.bodyHtml ?? "";
  const [draft, setDraft] = useState<string | null>(null);
  // React render-time adjustment: when the loaded template changes (first load
  // or revalidation after save) drop the draft so the editor re-seeds. A version
  // counter re-keys the editor, forcing a clean remount with the new content.
  const [baseline, setBaseline] = useState(storedBody);
  const [seed, setSeed] = useState(0);
  if (baseline !== storedBody) {
    setBaseline(storedBody);
    setDraft(null);
    setSeed((s) => s + 1);
  }

  const currentBody = draft ?? storedBody;
  const dirty = draft !== null && draft !== storedBody;

  const handleSave = () => {
    upsert.mutate({ bodyHtml: currentBody });
  };

  const handleClear = () => {
    upsert.mutate({ bodyHtml: "" });
  };

  if (isLoading) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-8 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  if (isError) {
    if (error instanceof SyncroApiError && error.status === 403) {
      return (
        <Alert variant="destructive">
          <TriangleAlert className="size-4" />
          <AlertDescription>{copy.accessDenied}</AlertDescription>
        </Alert>
      );
    }
    return (
      <Alert variant="destructive">
        <TriangleAlert className="size-4" />
        <AlertDescription>{copy.loadFailed}</AlertDescription>
      </Alert>
    );
  }

  return (
    <div className="space-y-4">
      <p className="text-muted-foreground text-sm">{copy.description}</p>
      {!template && <p className="text-muted-foreground text-sm italic">{copy.emptyHint}</p>}
      <RichTextEditor
        key={`${type}-${seed}`}
        initialContent={currentBody}
        onChange={setDraft}
        reportType={type}
        isSaving={upsert.isPending}
        canSave={dirty}
        onSave={handleSave}
      />
      {template && !isLayoutEmpty(template.bodyHtml) && (
        <div className="flex justify-end">
          <button
            type="button"
            className="text-muted-foreground hover:underline disabled:opacity-50 text-xs underline-offset-4"
            onClick={handleClear}
            disabled={upsert.isPending}
          >
            {copy.clear}
          </button>
        </div>
      )}
      <div className="flex justify-end">
        <button type="button" className="text-muted-foreground text-xs" onClick={() => void refetch()}>
          {copy.reload}
        </button>
      </div>
    </div>
  );
}

function ReportTemplatesContent() {
  const t = useTranslations("reportTemplates");
  const tWork = useTranslations("reportTemplates.tabs.workOrder");
  const tPrev = useTranslations("reportTemplates.tabs.preventive");
  const copyWork: TabCopy = {
    description: tWork("description"),
    emptyHint: tWork("emptyHint"),
    clear: tWork("clear"),
    reload: tWork("reload"),
    loadFailed: tWork("loadFailed"),
    accessDenied: tWork("accessDenied"),
  };
  const copyPrev: TabCopy = {
    description: tPrev("description"),
    emptyHint: tPrev("emptyHint"),
    clear: tPrev("clear"),
    reload: tPrev("reload"),
    loadFailed: tPrev("loadFailed"),
    accessDenied: tPrev("accessDenied"),
  };

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-lg font-semibold">{t("heading")}</h2>
        <p className="text-muted-foreground mt-1 text-sm">{t("pageDescription")}</p>
      </div>
      <Tabs defaultValue="WORK_ORDER">
        <TabsList>
          <TabsTrigger value="WORK_ORDER">{t("tabLabels.workOrder")}</TabsTrigger>
          <TabsTrigger value="PREVENTIVE">{t("tabLabels.preventive")}</TabsTrigger>
        </TabsList>
        <TabsContent value="WORK_ORDER" className="pt-4">
          <TemplateTab type="WORK_ORDER" copy={copyWork} />
        </TabsContent>
        <TabsContent value="PREVENTIVE" className="pt-4">
          <TemplateTab type="PREVENTIVE" copy={copyPrev} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

export function ReportTemplatesPageContent() {
  const t = useTranslations("reportTemplates");
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE"]} title={t("title")}>
      <ReportTemplatesContent />
    </RoleGuard>
  );
}
