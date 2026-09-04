package com.syncro.maintenance.preventive.infrastructure.db;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code pm_executions} (blueprint F7, story 15-2; queries for 19-5). */
public interface PmExecutionRepository extends JpaRepository<PmExecutionEntity, UUID> {

  Optional<PmExecutionEntity> findByPmWoId(UUID pmWoId);

  /** Start pre-check: one execution per PM work order (uq_pm_executions_pm_wo backstop). */
  boolean existsByPmWoId(UUID pmWoId);

  /** List filter: a technician's own executions, newest first. */
  List<PmExecutionEntity> findByTechnicianIdOrderByStartedAtDesc(UUID technicianId);

  /**
   * Pessimistic row lock for execution transitions (story 19-5): serializes
   * concurrent fill/complete/verify on the same row so the state precondition
   * cannot pass twice (19-4 findByIdForUpdate pattern).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select e from PmExecutionEntity e where e.id = :id")
  Optional<PmExecutionEntity> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Story 20-1 (AD-19/FR-170): completed PM executions whose PM workorder is scheduled
   * inside the month window — the PM-completion numerator. Review 20-1: attribution uses
   * the WO's scheduled month (same key as the planned denominator), so a WO scheduled in
   * July and completed in August counts into July's rate, never above 100%.
   * Plant resolves through the PM workorder → machine → plant.
   */
  @Query("""
      select count(e) from PmExecutionEntity e
      join PmWorkOrderEntity w on w.id = e.pmWoId
      join MachineEntity m on m.id = w.machineId
      where m.plant.id = :plantId
        and w.scheduledDate >= :monthFrom and w.scheduledDate < :monthTo
        and e.completedAt is not null
      """)
  long countCompletedForPlantInMonth(@Param("plantId") UUID plantId,
      @Param("monthFrom") java.time.LocalDate monthFrom,
      @Param("monthTo") java.time.LocalDate monthTo);
}
