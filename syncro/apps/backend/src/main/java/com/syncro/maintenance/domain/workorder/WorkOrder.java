package com.syncro.maintenance.domain.workorder;

import java.time.Instant;
import java.util.UUID;

/**
 * Application-level workorder value (AD-3/AD-4). The id is dual-source: an external
 * {@code sheet_no} (source SYNCED) or {@code WO-YYMM-XXXXX} (source INTERNAL).
 * Cross-aggregate references stay as plain ids — this record carries no JPA/Spring
 * state and is mapped to/from the persistence entity by {@code WorkOrderMapper}.
 */
public record WorkOrder(
    String id,
    String source,
    WorkOrderStatus status,
    UUID categoryId,
    UUID machineId,
    String description,
    String parentId,
    UUID assignedTechnicianId,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt,
    Long mttrMinutes,
    Long responseTimeMinutes,
    String doneReason) {
}
