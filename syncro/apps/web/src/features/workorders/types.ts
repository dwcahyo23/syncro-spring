/**
 * Workorder todo & kanban contract types (story 10-7, FR-119). These mirror the backend
 * DTOs exactly (TodoView, WorkOrderKanbanItem, KanbanView) — they will be superseded by
 * the orval-generated client once the OpenAPI snapshot is refreshed, and are kept local
 * to the feature until then.
 */
export type TodoStatus = "PENDING" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED";

export interface TodoView {
  id: string;
  workorderId: string;
  title: string;
  description: string | null;
  assignedTechnicianId: string | null;
  status: TodoStatus;
  sortOrder: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface WorkOrderKanbanItem {
  id: string;
  status: string;
  categoryCode: string | null;
  machineId: string;
  description: string | null;
  assignedTechnicianId: string | null;
  createdAt: string;
  todos: TodoView[];
}

export interface KanbanView {
  groups: Record<string, WorkOrderKanbanItem[]>;
}

export interface CreateTodoRequest {
  title: string;
  description?: string | null;
  assignedTechnicianId?: string | null;
}

export interface AssignTodoRequest {
  assignedTechnicianId: string;
}

export interface ReorderTodoRequest {
  sortOrder: number;
}
