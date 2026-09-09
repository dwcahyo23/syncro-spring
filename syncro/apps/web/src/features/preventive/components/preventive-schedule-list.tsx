"use client";

import { useState } from "react";

import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { PreventiveScheduleDetail } from "@/features/preventive/components/preventive-schedule-detail";
import { usePreventiveSchedules } from "@/features/preventive/hooks/use-preventive";
import type { PreventiveScheduleView } from "@/features/preventive/types";

/**
 * Due/overdue preventive schedule list (story 11-1, FR-131 + story 11-2, FR-132).
 * Status is server-derived (OVERDUE when SCHEDULED and past due); overdue is shown as
 * badge + text, never color alone. Rows open the checklist/evidence/approval detail.
 */
export function PreventiveScheduleList() {
  const t = useTranslations("preventive");
  const tc = useTranslations("common");
  const { data, isLoading, isError, refetch } = usePreventiveSchedules();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = selectedId ? (data ?? []).find((s) => s.id === selectedId) : null;

  if (isLoading) {
    return (
      <div className="space-y-2">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    );
  }

  if (isError) {
    return (
      <div className="flex flex-col items-center gap-3 rounded-lg border p-6">
        <p className="text-muted-foreground text-sm">{t("schedules.loadFailed")}</p>
        <Button type="button" variant="outline" size="sm" onClick={() => void refetch()}>
          {tc("retry")}
        </Button>
      </div>
    );
  }

  const schedules = data ?? [];
  if (schedules.length === 0) {
    return (
      <div className="rounded-lg border p-6">
        <p className="text-muted-foreground text-sm">{t("schedules.empty")}</p>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {selected ? (
        <PreventiveScheduleDetail schedule={selected} onClose={() => setSelectedId(null)} />
      ) : (
        <div className="space-y-2">
          {schedules.map((schedule) => (
            <ScheduleRow key={schedule.id} schedule={schedule} onOpen={() => setSelectedId(schedule.id)} />
          ))}
        </div>
      )}
    </div>
  );
}

function ScheduleRow({ schedule, onOpen }: { schedule: PreventiveScheduleView; onOpen: () => void }) {
  const t = useTranslations("preventive");
  const overdue = schedule.derivedStatus === "OVERDUE";
  return (
    <button
      type="button"
      onClick={onOpen}
      className="flex w-full items-center justify-between gap-2 rounded-lg border p-3 text-left hover:bg-muted"
    >
      <div className="min-w-0">
        <p className="font-medium text-sm">
          {schedule.dueDate} · {schedule.category} · {schedule.scheduleType}
        </p>
        <p className="text-muted-foreground text-xs">
          {t("machine")} <span className="font-mono">{schedule.machineId.slice(0, 8)}</span>
          {schedule.shiftConfig
            ? t("schedules.shiftWithSource", { source: schedule.shiftConfig.source })
            : t("schedules.shiftNone")}
        </p>
      </div>
      <div className="flex items-center gap-2">
        <Badge variant="secondary">{schedule.checklistStatus}</Badge>
        {overdue ? (
          <Badge variant="destructive">{t("overdue")}</Badge>
        ) : (
          <Badge variant="outline">{schedule.derivedStatus}</Badge>
        )}
      </div>
    </button>
  );
}
