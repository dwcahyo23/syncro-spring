package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.RatingType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Workorder rating persistence (story 10-8, FR-121/FR-124). Ratings are immutable; the
 * existence checks mirror the DB unique constraints (one TECHNICIAN rating per
 * (workorder, rated_user); one WORKORDER rating per workorder — partial unique index).
 */
public interface WorkorderRatingRepository extends JpaRepository<WorkorderRatingEntity, UUID> {

  List<WorkorderRatingEntity> findByWorkorderIdOrderByCreatedAtAsc(String workorderId);

  /** TECHNICIAN-type existence check — ratedUserId is never null for this type. */
  Optional<WorkorderRatingEntity> findByWorkorderIdAndRatingTypeAndRatedUserId(
      String workorderId, RatingType ratingType, UUID ratedUserId);

  /** WORKORDER-type existence check — no rated user; one per workorder. */
  boolean existsByWorkorderIdAndRatingType(String workorderId, RatingType ratingType);
}