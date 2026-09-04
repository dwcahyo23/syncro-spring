package com.syncro.kpi.infrastructure.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code kpi_technician_monthlies} (blueprint G6, story 15-2; reads for 20-1). */
public interface KpiTechnicianMonthlyRepository extends JpaRepository<KpiTechnicianMonthlyEntity, UUID> {

  Optional<KpiTechnicianMonthlyEntity> findByPlantIdAndTechnicianIdAndMonth(UUID plantId,
      UUID technicianId, java.time.LocalDate month);

  List<KpiTechnicianMonthlyEntity> findByMonth(java.time.LocalDate month);
}
