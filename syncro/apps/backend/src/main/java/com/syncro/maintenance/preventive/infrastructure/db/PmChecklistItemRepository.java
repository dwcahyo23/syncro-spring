package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code pm_checklist_items} (blueprint F4, story 15-2). */
public interface PmChecklistItemRepository extends JpaRepository<PmChecklistItemEntity, UUID> {

  List<PmChecklistItemEntity> findByChecksheetIdOrderBySequenceAsc(UUID checksheetId);
}
