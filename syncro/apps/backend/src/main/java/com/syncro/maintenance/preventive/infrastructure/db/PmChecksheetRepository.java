package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code pm_checksheets} (blueprint F2, story 15-2). */
public interface PmChecksheetRepository extends JpaRepository<PmChecksheetEntity, UUID> {

  Optional<PmChecksheetEntity> findByMachineIdAndFrequencyIdAndRevisionNo(UUID machineId,
      UUID frequencyId, int revisionNo);
}
