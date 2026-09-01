package com.syncro.kpi.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code kpi_mar_monthlies} (blueprint G5, story 15-2). */
public interface KpiMarMonthlyRepository extends JpaRepository<KpiMarMonthlyEntity, UUID> {

  Optional<KpiMarMonthlyEntity> findByPlantIdAndMonth(UUID plantId, java.time.LocalDate month);
}
