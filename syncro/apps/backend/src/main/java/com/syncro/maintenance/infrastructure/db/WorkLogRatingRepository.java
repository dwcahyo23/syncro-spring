package com.syncro.maintenance.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code work_log_ratings} (blueprint C2, story 15-2). */
public interface WorkLogRatingRepository extends JpaRepository<WorkLogRatingEntity, UUID> {

  List<WorkLogRatingEntity> findByWorkLogId(UUID workLogId);

  boolean existsByCriterionId(UUID criterionId);

  boolean existsByWorkLogIdAndCriterionId(UUID workLogId, UUID criterionId);
}
