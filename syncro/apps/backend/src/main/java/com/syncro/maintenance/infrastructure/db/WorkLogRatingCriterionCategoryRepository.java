package com.syncro.maintenance.infrastructure.db;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code work_log_rating_criterion_categories} (blueprint C1, story 15-2). */
public interface WorkLogRatingCriterionCategoryRepository
    extends JpaRepository<WorkLogRatingCriterionCategoryEntity, UUID> {
}
