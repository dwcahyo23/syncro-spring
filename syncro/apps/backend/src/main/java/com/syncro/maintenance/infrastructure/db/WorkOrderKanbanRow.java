package com.syncro.maintenance.infrastructure.db;

/**
 * One flat kanban row (story 10-7): a workorder, its category (nullable), and one todo
 * (null when the workorder has none — the LEFT JOIN produced no todo row). Rows are
 * ordered by workorder status/id so a workorder's rows are contiguous; the application
 * layer groups them into status → workorder items.
 */
public record WorkOrderKanbanRow(WorkOrderEntity workOrder, WorkOrderCategoryEntity category,
    WorkOrderTodoEntity todo) {
}
