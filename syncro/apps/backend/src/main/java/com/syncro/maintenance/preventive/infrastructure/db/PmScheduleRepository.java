package com.syncro.maintenance.preventive.infrastructure.db;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code pm_schedules} (blueprint F5, story 15-2). */
public interface PmScheduleRepository extends JpaRepository<PmScheduleEntity, UUID> {

  Optional<PmScheduleEntity> findByPlantIdAndMachineIdAndChecksheetIdAndYear(UUID plantId,
      UUID machineId, UUID checksheetId, int year);

  /**
   * Pessimistic row lock for approval/date transitions (story 19-3): serializes
   * concurrent submit/approve/activate/date-transition on the same row so the
   * state precondition cannot pass twice (18-5 findByIdForUpdate pattern).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from PmScheduleEntity s where s.id = :id")
  Optional<PmScheduleEntity> findByIdForUpdate(@Param("id") UUID id);
}
