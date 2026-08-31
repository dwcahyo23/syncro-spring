"use client";

import { WorkOrderCard } from "@/features/workorders/components/workorder-card";
import type { WorkOrderKanbanItem } from "@/features/workorders/types";

export interface KanbanColumnProps {
  title: string;
  status: string;
  items: WorkOrderKanbanItem[];
}

export function KanbanColumn({ title, status, items }: KanbanColumnProps) {
  const dotClass = statusDotClass(status);
  return (
    <section
      aria-label={`${title} workorders`}
      className="flex min-h-64 w-72 shrink-0 flex-col rounded-lg bg-muted/40 p-3"
    >
      <header className="mb-3 flex items-center justify-between">
        <h3 className="flex items-center gap-2 font-semibold text-sm">
          <span aria-hidden="true" className={`size-2 rounded-full ${dotClass}`} />
          {title}
        </h3>
        <span className="rounded-full bg-background px-2 py-0.5 text-muted-foreground text-xs">{items.length}</span>
      </header>
      <div className="flex-1 space-y-2 overflow-y-auto" data-kanban-status={status}>
        {items.length === 0 ? (
          <p className="rounded-md border border-dashed p-4 text-center text-muted-foreground text-xs">No workorders</p>
        ) : (
          items.map((item) => <WorkOrderCard key={item.id} item={item} />)
        )}
      </div>
    </section>
  );
}

function statusDotClass(status: string): string {
  switch (status) {
    case "OPEN":
      return "status-icon-critical";
    case "ASSIGNED":
    case "IN_PROGRESS":
      return "status-icon-warning";
    case "ON_PROCUREMENT":
      return "status-icon-info";
    case "DONE":
    case "CLOSED":
      return "status-icon-healthy";
    case "CANCELLED":
      return "status-icon-neutral";
    default:
      return "status-icon-neutral";
  }
}
