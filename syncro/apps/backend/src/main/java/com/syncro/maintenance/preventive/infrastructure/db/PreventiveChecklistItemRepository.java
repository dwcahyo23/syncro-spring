package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreventiveChecklistItemRepository extends JpaRepository<PreventiveChecklistItemEntity, UUID> {

  List<PreventiveChecklistItemEntity> findByResultIdOrderByPositionAsc(UUID resultId);

  void deleteByResultId(UUID resultId);
}
