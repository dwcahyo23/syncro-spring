package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code pm_checklist_items} (blueprint F4, story 15-2). */
public interface PmChecklistItemRepository extends JpaRepository<PmChecklistItemEntity, UUID> {

  List<PmChecklistItemEntity> findByChecksheetIdOrderBySequenceAsc(UUID checksheetId);

  List<PmChecklistItemEntity> findByChecksheetIdAndCategoryIdOrderBySequenceAsc(UUID checksheetId,
      UUID categoryId);

  /** Items grouped under a category — orphaned (category_id nulled) on category delete. */
  List<PmChecklistItemEntity> findByCategoryId(UUID categoryId);

  /** Highest sequence in a checksheet; empty when it has no items (default-sequence max+1). */
  @Query("select max(i.sequence) from PmChecklistItemEntity i where i.checksheetId = :checksheetId")
  Optional<Integer> findMaxSequence(@Param("checksheetId") UUID checksheetId);
}
