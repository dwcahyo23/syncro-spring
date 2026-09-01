package com.syncro.maintenance.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code work_log_rating_criteria} (blueprint C1, story 15-2). */
public interface WorkLogRatingCriterionRepository extends JpaRepository<WorkLogRatingCriterionEntity, UUID> {

  List<WorkLogRatingCriterionEntity> findAllByOrderBySortOrderAsc();
}
