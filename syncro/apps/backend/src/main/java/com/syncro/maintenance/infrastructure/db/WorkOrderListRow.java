package com.syncro.maintenance.infrastructure.db;

/**
 * One flat list row (story workorder-table): a workorder, its category (nullable), the
 * machine code/name, and the plant code. No todos — the cartesian blowup on hundreds of
 * rows is avoided by leaving todos out of the list query. Technician name is resolved
 * in the application layer via a second {@code findAllById} call.
 */
public record WorkOrderListRow(WorkOrderEntity workOrder, WorkOrderCategoryEntity category,
    String machineCode, String machineName, String plantCode) {
}