package com.syncro.maintenance.infrastructure.db;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code work_order_rating_criteria} (blueprint C3, story 15-2). */
public interface WorkOrderRatingCriterionRepository extends JpaRepository<WorkOrderRatingCriterionEntity, UUID> {
}
