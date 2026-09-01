package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code pm_execution_items} (blueprint F8, story 15-2). */
public interface PmExecutionItemRepository extends JpaRepository<PmExecutionItemEntity, UUID> {

  List<PmExecutionItemEntity> findByExecutionIdOrderBySequenceAsc(UUID executionId);
}
