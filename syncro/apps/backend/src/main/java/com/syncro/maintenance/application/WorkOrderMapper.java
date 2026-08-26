package com.syncro.maintenance.application;

import com.syncro.maintenance.domain.workorder.WorkOrder;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;

/** Maps a persisted workorder entity to the domain value. */
public final class WorkOrderMapper {
  private WorkOrderMapper() {
  }

  public static WorkOrder toDomain(WorkOrderEntity entity) {
    return new WorkOrder(entity.getId(), entity.getSource(), entity.getStatus(), entity.getCategoryId(),
        entity.getMachineId(), entity.getDescription(), entity.getParentId(), entity.getAssignedTechnicianId(),
        entity.getCreatedBy(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getMttrMinutes(),
        entity.getResponseTimeMinutes(), entity.getDoneReason(), entity.getReportChronological(),
        entity.getReportAnalyze(), entity.getReportCorrective(), entity.getReportPreventive(),
        entity.getCpCkLower(), entity.getCpCkUpper(), entity.getCpk(), entity.getCpkPdfObjectKey(),
        entity.getFmeaFailureType(), entity.getStopTimeReason(), entity.getStopTimeDetail());
  }
}
