"use client";

import { DndContext, type DragEndEvent, PointerSensor, useSensor, useSensors } from "@dnd-kit/core";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { KanbanColumn } from "@/features/workorders/components/kanban-column";
import { useKanban } from "@/features/workorders/hooks/use-kanban";
import type { KanbanView } from "@/features/workorders/types";

/**
 * Kanban board (story 10-7, FR-119). v1 is a grouped read view — columns are ordered by
 * the workorder status lifecycle and never reordered across columns. dnd-kit wraps the
 * board so todo drag interactions can be wired in a future iteration; the transition
 * call is deliberately stubbed (the spec's Never section: no drag-to-reorder across
 * status columns in v1).
 */
const COLUMNS: { status: string }[] = [
  { status: "DRAFT" },
  { status: "OPEN" },
  { status: "ASSIGNED" },
  { status: "IN_PROGRESS" },
  { status: "ON_PROCUREMENT" },
];

export function KanbanBoard() {
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const { data, isLoading, isError, refetch } = useKanban();
  const queryClient = useQueryClient();
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 8 } }));

  const handleDragEnd = (_event: DragEndEvent) => {
    // v1: the board is read-only for status changes. Reordering workorders across
    // status columns would require the transition endpoint (POST /{id}/transition) —
    // deliberately not wired here (story 10-7 Never section).
    void queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders/kanban"] });
  };

  if (isLoading) {
    return (
      <div className="flex gap-4 overflow-x-auto pb-4">
        {COLUMNS.map((column) => (
          <div key={column.status} className="w-72 shrink-0 space-y-3">
            <Skeleton className="h-6 w-32" />
            <Skeleton className="h-40 w-full" />
            <Skeleton className="h-40 w-full" />
          </div>
        ))}
      </div>
    );
  }

  if (isError) {
    return (
      <div className="flex flex-col items-center gap-3 rounded-lg border p-6">
        <p className="text-muted-foreground text-sm">{t("kanban.loadFailed")}</p>
        <Button type="button" variant="outline" size="sm" onClick={() => void refetch()}>
          {tc("retry")}
        </Button>
      </div>
    );
  }

  const view: KanbanView = data ?? { groups: {} };

  return (
    <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
      <div className="flex gap-4 overflow-x-auto pb-4">
        {COLUMNS.map((column) => (
          <KanbanColumn
            key={column.status}
            title={t.has(`status.${column.status}`) ? t(`status.${column.status}`) : column.status}
            status={column.status}
            items={view.groups[column.status] ?? []}
          />
        ))}
      </div>
    </DndContext>
  );
}
