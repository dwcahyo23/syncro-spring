package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import java.util.UUID;

/**
 * One (technician, workorder) objective-KPI row (story 14-2, FR-174). Produced by two
 * sources — workorder {@code assignedTechnicianId} and repair-session {@code technicianId} —
 * and deduplicated by (technician, workorder) in the application layer. {@code plantId}
 * supports the optional plant filter passthrough. {@code categoryCode} is the workorder
 * category code (null when the workorder has no category) so the application layer can
 * scope average MTTR to breakdown workorders only (FR-173/174 — MTTR is a breakdown
 * metric).
 */
public record TechnicianKpiRow(
    UUID technicianId,
    String workOrderId,
    WorkOrderStatus status,
    UUID plantId,
    String categoryCode,
    Long mttrMinutes,
    Long responseTimeMinutes,
    Integer targetResponseMinutes) {
}
