package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One breakdown-workorder analytics row (story 14-2, FR-173). {@code woStopAt} is the
 * DERIVED stop time — the latest DONE transition from {@code work_order_status_history}
 * (fallback {@code updatedAt} when no DONE row exists, the sync edge case). The window
 * filter is keyed on this derived stop time, per the 14-2 design note. {@code plantId}
 * supports the optional plant filter passthrough (out-of-scope plantId → empty payload).
 */
public record WorkOrderAnalyticsRow(
    String id,
    WorkOrderStatus status,
    UUID machineId,
    UUID plantId,
    UUID assignedTechnicianId,
    Long mttrMinutes,
    Long responseTimeMinutes,
    String categoryCode,
    Integer targetResponseMinutes,
    Instant woStopAt) {
}
