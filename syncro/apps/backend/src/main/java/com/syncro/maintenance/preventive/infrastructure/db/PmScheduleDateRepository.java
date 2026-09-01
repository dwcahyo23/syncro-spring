package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code pm_schedule_dates} (blueprint F5, story 15-2). */
public interface PmScheduleDateRepository extends JpaRepository<PmScheduleDateEntity, UUID> {

  List<PmScheduleDateEntity> findByScheduleIdOrderByPlannedDateAsc(UUID scheduleId);
}
