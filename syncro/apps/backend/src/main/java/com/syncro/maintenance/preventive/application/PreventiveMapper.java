package com.syncro.maintenance.preventive.application;

import com.syncro.maintenance.preventive.domain.PreventiveProgram;
import com.syncro.maintenance.preventive.domain.PreventiveSchedule;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveProgramEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleEntity;

/** Maps persisted preventive entities to their domain values. */
public final class PreventiveMapper {
  private PreventiveMapper() {
  }

  public static PreventiveProgram toDomain(PreventiveProgramEntity entity) {
    return new PreventiveProgram(entity.getId(), entity.getMachineId(), entity.getCategory(),
        entity.getScheduleType(), entity.getDayOfMonth(),
        entity.getMonthOfYear() != null ? entity.getMonthOfYear().intValue() : null, entity.getTitle(),
        entity.getDescription(), entity.isActive(), entity.getCreatedBy(), entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  public static PreventiveSchedule toDomain(PreventiveScheduleEntity entity) {
    return new PreventiveSchedule(entity.getId(), entity.getProgramId(), entity.getMachineId(),
        entity.getDueDate(), entity.getStatus(), entity.getCompletedAt(), entity.getPerformedBy(),
        entity.getCreatedAt(), entity.getUpdatedAt());
  }

  public static com.syncro.maintenance.preventive.domain.PreventiveChecklistResult checklistToDomain(
      com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistResultEntity entity) {
    return new com.syncro.maintenance.preventive.domain.PreventiveChecklistResult(entity.getId(),
        entity.getScheduleId(), entity.getPerformedBy(), entity.getCompletedAt(), entity.getNotes(),
        entity.getLeaderId(), entity.getAssessment(), entity.getApprovedAt(), entity.getSignatureObjectKey(),
        entity.getSignerIdentity(), entity.getCreatedAt(), entity.getUpdatedAt());
  }
}