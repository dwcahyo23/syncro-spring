package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreventiveScheduleAttachmentRepository
    extends JpaRepository<PreventiveScheduleAttachmentEntity, UUID> {

  List<PreventiveScheduleAttachmentEntity> findByScheduleIdOrderByCreatedAtAsc(UUID scheduleId);

  Optional<PreventiveScheduleAttachmentEntity> findByIdAndScheduleId(UUID id, UUID scheduleId);
}
