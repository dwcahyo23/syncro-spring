package com.syncro.maintenance.infrastructure.db;

/**
 * One flat ratings-page row (story 10-8): a CLOSED workorder with its category (nullable
 * — a workorder may not have a category set). Rows are scope-filtered by machine
 * plant/group and ordered by workorder id; the application layer attaches rating status
 * and the executor pool.
 */
public record WorkOrderRatingRow(WorkOrderEntity workOrder, WorkOrderCategoryEntity category) {
}
