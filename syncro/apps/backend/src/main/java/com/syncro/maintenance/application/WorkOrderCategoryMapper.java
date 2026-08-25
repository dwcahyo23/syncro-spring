package com.syncro.maintenance.application;

import com.syncro.maintenance.domain.workorder.WorkOrderCategory;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;

/** Maps a persisted work-order category entity to the domain value. */
public final class WorkOrderCategoryMapper {
  private WorkOrderCategoryMapper() {
  }

  public static WorkOrderCategory toDomain(WorkOrderCategoryEntity entity) {
    return new WorkOrderCategory(entity.getCode(), entity.getLabel());
  }
}
