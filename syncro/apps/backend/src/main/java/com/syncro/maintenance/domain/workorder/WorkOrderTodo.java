package com.syncro.maintenance.domain.workorder;

import java.time.Instant;
import java.util.UUID;

/**
 * Application-level workorder todo value (FR-119, story 10-7). The id is a UUID
 * generated server-side. Cross-aggregate references stay as plain ids — this record
 * carries no JPA/Spring state and is mapped to/from the persistence entity by
 * WorkOrderMapper.
 */
public record WorkOrderTodo(
    UUID id,
    String workorderId,
    String title,
    String description,
    UUID assignedTechnicianId,
    TodoStatus status,
    int sortOrder,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {
}