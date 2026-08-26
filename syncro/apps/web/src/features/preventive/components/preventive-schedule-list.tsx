"use client";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { usePreventiveSchedules } from "@/features/preventive/hooks/use-preventive";
import type { PreventiveScheduleView } from "@/features/preventive/types";

/**
 * Due/overdue preventive schedule list (story 11-1, FR-131). Status is server-derived
 * (OVERDUE when SCHEDULED and past due). Overdue is shown as badge + text, never color
 * alone. Shift context is surfaced for the calendar basis (AD-12).
 */
export function PreventiveScheduleList() {
  const { data, isLoading, isError, refetch } = usePreventiveSchedules();

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
        <p className="text-muted-foreground text-sm">Failed to load preventive schedules.</p>
        <Button type="button" variant="outline" size="sm" onClick={() => void refetch()}>
          Retry
        </Button>
      </div>
    );
  }

  const schedules = data ?? [];
  if (schedules.length === 0) {
    return (
      <div className="rounded-lg border p-6">
        <p className="text-muted-foreground text-sm">No preventive schedules in your scope yet.</p>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {schedules.map((schedule) => (
        <ScheduleRow key={schedule.id} schedule={schedule} />
      ))}
    </div>
  );
}

function ScheduleRow({ schedule }: { schedule: PreventiveScheduleView }) {
  const overdue = schedule.derivedStatus === "OVERDUE";
  return (
    <div className="flex items-center justify-between gap-2 rounded-lg border p-3">
      <div className="min-w-0">
        <p className="font-medium text-sm">
          {schedule.dueDate} · {schedule.category} · {schedule.scheduleType}
        </p>
        <p className="text-muted-foreground text-xs">
          Machine <span className="font-mono">{schedule.machineId.slice(0, 8)}</span>
          {schedule.shiftConfig ? ` · shift: ${schedule.shiftConfig.source}` : " · shift: none"}
        </p>
      </div>
      {overdue ? (
        <Badge variant="destructive">Overdue</Badge>
      ) : (
        <Badge variant="outline">{schedule.derivedStatus}</Badge>
      )}
    </div>
  );
}
