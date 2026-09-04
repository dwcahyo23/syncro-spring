package com.syncro.kpi.infrastructure.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code kpi_pm_completion_monthlies} (blueprint G7, story 15-2; reads for 20-1). */
public interface KpiPmCompletionMonthlyRepository extends JpaRepository<KpiPmCompletionMonthlyEntity, UUID> {

  Optional<KpiPmCompletionMonthlyEntity> findByPlantIdAndMonth(UUID plantId, java.time.LocalDate month);

  List<KpiPmCompletionMonthlyEntity> findByMonth(java.time.LocalDate month);
}
