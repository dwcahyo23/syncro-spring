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

// ---------------------------------------------------------------------------
// Workorder list table (workorder-table story)
// ---------------------------------------------------------------------------

/** One server-paginated list row (workorder-table story). Raw UUIDs exist for keys/actions but are never rendered. */
export interface WorkOrderListRow {
  id: string;
  status: string;
  categoryCode: string | null;
  categoryLabel: string | null;
  machineCode: string | null;
  machineName: string | null;
  plantCode: string | null;
  assignedTechnicianId: string | null;
  assignedTechnicianName: string | null;
  description: string | null;
  createdAt: string;
  updatedAt: string;
  doneReason: string | null;
}

/** Server-paginated list envelope: current page items + matching count + page/size echo. */
export interface WorkOrderPage {
  items: WorkOrderListRow[];
  total: number;
  page: number;
  size: number;
}

/** Query params for GET /api/v1/workorders. */
export interface WorkOrderListParams {
  from?: string;
  to?: string;
  status?: string;
  machineId?: string;
  search?: string;
  page: number;
  size: number;
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

// ---------------------------------------------------------------------------
// Ratings (10.8, FR-121/FR-124)
// ---------------------------------------------------------------------------

export type RatingType = "TECHNICIAN" | "WORKORDER";

export interface RatingScoreView {
  dimensionCode: string;
  score: number;
}

export interface RatingView {
  id: string;
  workorderId: string;
  ratingType: RatingType;
  ratedUserId: string | null;
  raterUserId: string;
  createdAt: string;
  scores: RatingScoreView[];
}

export interface RatingDimensionView {
  id: string;
  code: string;
  label: string;
  sortOrder: number;
}

export interface RateTechnicianRequest {
  ratedUserId: string;
  scores: Record<string, number>;
}

export interface RateWorkorderRequest {
  scores: Record<string, number>;
}

export interface CreateRatingDimensionRequest {
  code: string;
  label: string;
  sortOrder?: number;
}

export interface UpdateRatingDimensionRequest {
  label: string;
  sortOrder?: number;
}

export interface RateableWorkorderView {
  id: string;
  source: string;
  status: string;
  categoryCode: string | null;
  machineId: string;
  description: string | null;
  assignedTechnicianId: string | null;
  createdAt: string;
  executorPool: string[];
}
