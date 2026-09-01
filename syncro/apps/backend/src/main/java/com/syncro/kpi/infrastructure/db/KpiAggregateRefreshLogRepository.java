package com.syncro.kpi.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code kpi_aggregate_refresh_logs} (blueprint G7, story 15-2). */
public interface KpiAggregateRefreshLogRepository extends JpaRepository<KpiAggregateRefreshLogEntity, UUID> {

  Optional<KpiAggregateRefreshLogEntity> findByRefreshKey(String refreshKey);
}
