import { KanbanBoard } from "@/features/workorders/components/kanban-board";

/**
 * Kanban board route (story 10-7, FR-119). Server Component wrapper — the interactive
 * board lives in the client {@link KanbanBoard} component. The backend returns
 * scope-filtered workorders grouped by status with embedded todos, so no client-side
 * permission logic is needed here.
 */
export default function WorkordersKanbanPage() {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="font-semibold text-xl">Workorders Kanban</h1>
        <p className="text-muted-foreground text-sm">Open workorders grouped by status, with their per-task todos.</p>
      </div>
      <KanbanBoard />
    </div>
  );
}
