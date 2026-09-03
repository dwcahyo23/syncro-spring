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
}
