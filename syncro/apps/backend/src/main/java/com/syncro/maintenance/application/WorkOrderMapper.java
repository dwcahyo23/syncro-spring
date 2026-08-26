package com.syncro.maintenance.application;

import com.syncro.maintenance.domain.workorder.WorkOrder;
import com.syncro.maintenance.domain.workorder.WorkOrderTodo;
import com.syncro.maintenance.domain.workorder.WorkorderRating;
import com.syncro.maintenance.infrastructure.db.RatingDimensionEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderTodoEntity;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingEntity;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingScoreEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps persisted workorder/todo/rating entities to their domain values. */
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

  /**
   * Story 10-8 (FR-121/FR-124): maps a persisted rating row plus its score rows to the
   * domain value. The scores map is keyed by the dimension code (the API contract) —
   * resolved from the dimension entities.
   */
  public static WorkorderRating toDomain(WorkorderRatingEntity entity, List<WorkorderRatingScoreEntity> scores,
      List<RatingDimensionEntity> dimensions) {
    var codeByDimensionId = new LinkedHashMap<java.util.UUID, String>();
    for (var dimension : dimensions) {
      codeByDimensionId.put(dimension.getId(), dimension.getCode());
    }
    var scoreMap = new LinkedHashMap<String, Integer>();
    for (var score : scores) {
      var code = codeByDimensionId.get(score.getDimensionId());
      if (code != null) {
        scoreMap.put(code, (int) score.getScore());
      }
    }
    return new WorkorderRating(entity.getId(), entity.getWorkorderId(), entity.getRatingType(),
        entity.getRatedUserId(), entity.getRaterUserId(), entity.getCreatedAt(), scoreMap);
  }
}
