package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code active_checksheets} (blueprint F3, story 15-2). */
public interface ActiveChecksheetRepository extends JpaRepository<ActiveChecksheetEntity, ActiveChecksheetId> {

  Optional<ActiveChecksheetEntity> findByIdMachineIdAndIdFrequencyId(UUID machineId, UUID frequencyId);
}
