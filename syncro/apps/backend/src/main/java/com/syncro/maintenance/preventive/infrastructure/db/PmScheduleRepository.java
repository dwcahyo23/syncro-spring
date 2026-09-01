package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code pm_schedules} (blueprint F5, story 15-2). */
public interface PmScheduleRepository extends JpaRepository<PmScheduleEntity, UUID> {

  Optional<PmScheduleEntity> findByPlantIdAndMachineIdAndChecksheetIdAndYear(UUID plantId,
      UUID machineId, UUID checksheetId, int year);
}
