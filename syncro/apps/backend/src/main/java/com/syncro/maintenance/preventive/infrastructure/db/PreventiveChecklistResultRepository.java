package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreventiveChecklistResultRepository extends JpaRepository<PreventiveChecklistResultEntity, UUID> {

  Optional<PreventiveChecklistResultEntity> findByScheduleId(UUID scheduleId);

  boolean existsByScheduleId(UUID scheduleId);
}
