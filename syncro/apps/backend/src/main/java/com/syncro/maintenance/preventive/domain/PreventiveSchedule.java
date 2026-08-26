package com.syncro.maintenance.preventive.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A materialized preventive schedule instance (FR-131, AD-12, story 11-1). Generated
 * from a program's anchor schedule; the next due date rolls forward from completion
 * (floating interval). OVERDUE is derived server-side, never stored.
 */
public record PreventiveSchedule(
    UUID id,
    UUID programId,
    UUID machineId,
    LocalDate dueDate,
    ScheduleStatus status,
    Instant completedAt,
    UUID performedBy,
    Instant createdAt,
    Instant updatedAt) {
}