package com.syncro.org.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code plant_working_calendar_dates} (blueprint A12, story 15-2). */
public interface PlantWorkingCalendarDateRepository extends JpaRepository<PlantWorkingCalendarDateEntity, UUID> {

  List<PlantWorkingCalendarDateEntity> findByWorkingCalendarIdOrderByDateAsc(UUID workingCalendarId);
}
