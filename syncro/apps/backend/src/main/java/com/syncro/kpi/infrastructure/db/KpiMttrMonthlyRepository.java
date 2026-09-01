package com.syncro.kpi.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code kpi_mttr_monthlies} (blueprint G4, story 15-2). */
public interface KpiMttrMonthlyRepository extends JpaRepository<KpiMttrMonthlyEntity, UUID> {

  Optional<KpiMttrMonthlyEntity> findByPlantIdAndMonth(UUID plantId, java.time.LocalDate month);
}
