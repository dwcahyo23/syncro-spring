package com.syncro.maintenance.preventive.infrastructure.db;

import com.syncro.maintenance.preventive.domain.PreventiveCategory;
import com.syncro.maintenance.preventive.domain.ScheduleType;
import java.util.UUID;

/**
 * Dashboard-specific preventive schedule row (story 14-1, FR-172): extends the
 * base {@link PreventiveScheduleRow} with machine code/name and program title.
 */
public record PreventiveScheduleDashboardRow(
    PreventiveScheduleEntity schedule,
    PreventiveCategory category,
    ScheduleType scheduleType,
    UUID plantId,
    UUID machineGroupId,
    UUID machineId,
    String machineCode,
    String machineName,
    String programTitle) {
}
