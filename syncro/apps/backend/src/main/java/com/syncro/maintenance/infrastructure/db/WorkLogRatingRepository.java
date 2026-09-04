package com.syncro.maintenance.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code work_log_ratings} (blueprint C2, story 15-2). */
public interface WorkLogRatingRepository extends JpaRepository<WorkLogRatingEntity, UUID> {

  List<WorkLogRatingEntity> findByWorkLogId(UUID workLogId);

  boolean existsByCriterionId(UUID criterionId);

  boolean existsByWorkLogIdAndCriterionId(UUID workLogId, UUID criterionId);

  /**
   * Story 20-1 (FR-174): work-log rating scores for logs started in the window — the
   * technician average_rating source (configurable-dimension 1–5 stars).
   */
  @Query("""
      select new com.syncro.maintenance.infrastructure.db.KpiSourceRows$TechnicianRatingRow(
        l.technicianId, m.plant.id, w.id, r.score)
      from WorkLogRatingEntity r
      join WorkLogEntity l on l.id = r.workLogId
      join WorkOrderEntity w on w.id = l.workOrderId
      join MachineEntity m on m.id = w.machineId
      where l.startTime >= :from and l.startTime < :to
      """)
  List<KpiSourceRows.TechnicianRatingRow> findTechnicianRatingScores(
      @Param("from") java.time.Instant from, @Param("to") java.time.Instant to);
}
