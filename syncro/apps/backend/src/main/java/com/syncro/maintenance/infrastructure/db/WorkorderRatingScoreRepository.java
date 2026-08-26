package com.syncro.maintenance.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Workorder rating score persistence (story 10-8). Scores are always loaded/saved
 * together with their parent rating.
 */
public interface WorkorderRatingScoreRepository extends JpaRepository<WorkorderRatingScoreEntity, UUID> {

  List<WorkorderRatingScoreEntity> findByRatingId(UUID ratingId);

  void deleteByRatingId(UUID ratingId);

  boolean existsByDimensionId(UUID dimensionId);
}