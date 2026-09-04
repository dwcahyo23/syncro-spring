package com.syncro.kpi.infrastructure.db;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code kpi_targets} (blueprint G1, story 15-2). */
public interface KpiTargetRepository extends JpaRepository<KpiTargetEntity, UUID> {

  Optional<KpiTargetEntity> findByPlantIdAndMonth(UUID plantId, java.time.LocalDate month);

  List<KpiTargetEntity> findByPlantIdOrderByMonthAsc(UUID plantId);

  /**
   * Story 20-1 review: row lock for the upsert — serializes concurrent create/update on
   * the same (plant, month) so the find-then-save cannot race the unique constraint
   * (19-4 findByIdForUpdate pattern).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from KpiTargetEntity t where t.plantId = :plantId and t.month = :month")
  Optional<KpiTargetEntity> findByPlantIdAndMonthForUpdate(@Param("plantId") UUID plantId,
      @Param("month") java.time.LocalDate month);
}