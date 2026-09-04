package com.syncro.kpi.infrastructure.db;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code kpi_aggregate_refresh_logs} (blueprint G7, story 15-2). */
public interface KpiAggregateRefreshLogRepository extends JpaRepository<KpiAggregateRefreshLogEntity, UUID> {

  Optional<KpiAggregateRefreshLogEntity> findByRefreshKey(String refreshKey);

  /**
   * Story 20-1 review: row lock for the gate claim — serializes concurrent tryStart
   * for the same key so two passes cannot both observe a non-RUNNING row (19-4
   * findByIdForUpdate pattern).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from KpiAggregateRefreshLogEntity l where l.refreshKey = :refreshKey")
  Optional<KpiAggregateRefreshLogEntity> findByRefreshKeyForUpdate(@Param("refreshKey") String refreshKey);
}
