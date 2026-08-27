package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PreventiveChecklistResultRepository extends JpaRepository<PreventiveChecklistResultEntity, UUID> {

  Optional<PreventiveChecklistResultEntity> findByScheduleId(UUID scheduleId);

  boolean existsByScheduleId(UUID scheduleId);

  /** Batch checklist-status lookup for the calendar read (story 11-2): one query per list, no N+1. */
  @Query("select r.scheduleId from PreventiveChecklistResultEntity r where r.scheduleId in :scheduleIds")
  List<UUID> findScheduleIdsWithResult(@Param("scheduleIds") Collection<UUID> scheduleIds);

  @Query("select r.scheduleId from PreventiveChecklistResultEntity r where r.scheduleId in :scheduleIds and r.approvedAt is not null")
  List<UUID> findApprovedScheduleIds(@Param("scheduleIds") Collection<UUID> scheduleIds);
}
