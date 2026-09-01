package com.syncro.org.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code plant_working_calendars} (blueprint A12, story 15-2). */
public interface PlantWorkingCalendarRepository extends JpaRepository<PlantWorkingCalendarEntity, UUID> {

  Optional<PlantWorkingCalendarEntity> findByPlantIdAndYear(UUID plantId, int year);
}
