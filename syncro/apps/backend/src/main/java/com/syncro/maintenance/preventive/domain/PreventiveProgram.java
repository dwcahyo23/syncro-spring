package com.syncro.maintenance.preventive.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Preventive program definition (FR-130, story 11-1). A per-machine program with a
 * mechanical/electrical category and a MONTHLY/ANNUAL schedule type. The anchor
 * fields (dayOfMonth, monthOfYear) drive schedule generation. monthOfYear is null
 * for MONTHLY and required for ANNUAL.
 */
public record PreventiveProgram(
    UUID id,
    UUID machineId,
    PreventiveCategory category,
    ScheduleType scheduleType,
    int dayOfMonth,
    Integer monthOfYear,
    String title,
    String description,
    boolean active,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt) {
}