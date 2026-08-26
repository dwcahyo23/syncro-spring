package com.syncro.maintenance.preventive.infrastructure.db;

import com.syncro.maintenance.preventive.domain.PreventiveCategory;
import com.syncro.maintenance.preventive.domain.ScheduleType;
import java.util.UUID;

/**
 * One flat preventive-schedule row (story 11-1): the schedule plus the program's
 * category/schedule type and the machine's plant/group for scope filtering.
 */
public record PreventiveScheduleRow(
    PreventiveScheduleEntity schedule,
    PreventiveCategory category,
    ScheduleType scheduleType,
    UUID plantId,
    UUID machineGroupId,
    UUID machineId) {
}
