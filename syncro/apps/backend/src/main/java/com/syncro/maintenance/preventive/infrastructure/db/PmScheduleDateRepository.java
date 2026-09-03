package com.syncro.maintenance.preventive.infrastructure.db;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code pm_schedule_dates} (blueprint F5, story 15-2). */
public interface PmScheduleDateRepository extends JpaRepository<PmScheduleDateEntity, UUID> {

  List<PmScheduleDateEntity> findByScheduleIdOrderByPlannedDateAsc(UUID scheduleId);

  /** Period lookup (story 19-5): the schedule date a workorder's execution links to. */
  Optional<PmScheduleDateEntity> findByScheduleIdAndPlannedDate(UUID scheduleId,
      LocalDate plannedDate);
}
