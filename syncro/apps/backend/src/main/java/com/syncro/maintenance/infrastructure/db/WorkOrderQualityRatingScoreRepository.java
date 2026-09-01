package com.syncro.maintenance.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code work_order_quality_rating_scores} (blueprint C6, story 15-2). */
public interface WorkOrderQualityRatingScoreRepository
    extends JpaRepository<WorkOrderQualityRatingScoreEntity, UUID> {

  List<WorkOrderQualityRatingScoreEntity> findByQualityRatingId(UUID qualityRatingId);

  boolean existsByCriterionId(UUID criterionId);
}
