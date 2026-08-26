package com.syncro.maintenance.application;

import com.syncro.maintenance.domain.workorder.WorkOrder;
import com.syncro.maintenance.domain.workorder.WorkOrderTodo;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderTodoEntity;

/** Maps persisted workorder/todo entities to their domain values. */
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

  /** Story 10-7 (FR-119): maps a persisted todo row to the domain value. */
  public static WorkOrderTodo toDomain(WorkOrderTodoEntity entity) {
    return new WorkOrderTodo(entity.getId(), entity.getWorkorderId(), entity.getTitle(), entity.getDescription(),
        entity.getAssignedTechnicianId(), entity.getStatus(), entity.getSortOrder(), entity.getCreatedBy(),
        entity.getCreatedAt(), entity.getUpdatedAt(), entity.getCompletedAt());
  }
}
