package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code pm_checklist_categories} (blueprint F4, story 15-2). */
public interface PmChecklistCategoryRepository extends JpaRepository<PmChecklistCategoryEntity, UUID> {

  List<PmChecklistCategoryEntity> findByChecksheetIdOrderBySortOrderAsc(UUID checksheetId);
}
