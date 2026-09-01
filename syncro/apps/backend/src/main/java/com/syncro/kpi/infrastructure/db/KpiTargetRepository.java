package com.syncro.kpi.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code kpi_targets} (blueprint G1, story 15-2). */
public interface KpiTargetRepository extends JpaRepository<KpiTargetEntity, UUID> {

  Optional<KpiTargetEntity> findByPlantIdAndMonth(UUID plantId, java.time.LocalDate month);
}
