package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code pm_execution_items} (blueprint F8, story 15-2; queries for 19-5). */
public interface PmExecutionItemRepository extends JpaRepository<PmExecutionItemEntity, UUID> {

  List<PmExecutionItemEntity> findByExecutionIdOrderBySequenceAsc(UUID executionId);

  /** Fill lookup: the execution's snapshot row for one checklist item. */
  Optional<PmExecutionItemEntity> findByExecutionIdAndChecklistItemId(UUID executionId,
      UUID checklistItemId);
}
