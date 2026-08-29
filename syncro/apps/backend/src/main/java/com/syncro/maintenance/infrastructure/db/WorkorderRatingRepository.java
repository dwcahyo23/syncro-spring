package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.RatingType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Workorder rating persistence (story 10-8, FR-121/FR-124). Ratings are immutable; the
 * existence checks mirror the DB unique constraints (one TECHNICIAN rating per
 * (workorder, rated_user); one WORKORDER rating per workorder — partial unique index).
 *
 * <p>Story 14-2 (FR-174) adds the per-dimension average rating read for the technician
 * KPI dashboard.
 */
public interface WorkorderRatingRepository extends JpaRepository<WorkorderRatingEntity, UUID> {

  List<WorkorderRatingEntity> findByWorkorderIdOrderByCreatedAtAsc(String workorderId);

  /** TECHNICIAN-type existence check — ratedUserId is never null for this type. */
  Optional<WorkorderRatingEntity> findByWorkorderIdAndRatingTypeAndRatedUserId(
      String workorderId, RatingType ratingType, UUID ratedUserId);

  /** WORKORDER-type existence check — no rated user; one per workorder. */
  boolean existsByWorkorderIdAndRatingType(String workorderId, RatingType ratingType);

  /**
   * Per-dimension average score per rated user for TECHNICIAN-type ratings (FR-174).
   * Averaging happens in SQL (AVG) so the service never sees individual scores; results
   * are grouped by (rated user, dimension). The workorder set is already scope-filtered
   * by the caller ({@code workorderIds}).
   */
  @Query("""
      select new com.syncro.maintenance.infrastructure.db.RatingAverageRow(
        r.ratedUserId, s.dimensionId, avg(s.score))
      from WorkorderRatingEntity r
      join WorkorderRatingScoreEntity s on s.ratingId = r.id
      where r.ratingType = com.syncro.maintenance.domain.workorder.RatingType.TECHNICIAN
        and r.ratedUserId is not null
        and r.workorderId in :workorderIds
      group by r.ratedUserId, s.dimensionId
      """)
  List<RatingAverageRow> findRatingAveragesByWorkorderIds(
      @Param("workorderIds") Collection<String> workorderIds);
}