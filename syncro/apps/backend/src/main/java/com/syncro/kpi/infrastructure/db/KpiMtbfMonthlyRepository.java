package com.syncro.kpi.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code kpi_mtbf_monthlies} (blueprint G3, story 15-2). */
public interface KpiMtbfMonthlyRepository extends JpaRepository<KpiMtbfMonthlyEntity, UUID> {

  Optional<KpiMtbfMonthlyEntity> findByMachineIdAndMonth(UUID machineId, java.time.LocalDate month);
}
