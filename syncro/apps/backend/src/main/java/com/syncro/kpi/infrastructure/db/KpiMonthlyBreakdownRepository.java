package com.syncro.kpi.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code kpi_monthly_breakdowns} (blueprint G2, story 15-2). */
public interface KpiMonthlyBreakdownRepository extends JpaRepository<KpiMonthlyBreakdownEntity, UUID> {

  Optional<KpiMonthlyBreakdownEntity> findByPlantIdAndMonth(UUID plantId, java.time.LocalDate month);
}
