package com.syncro.sync.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA repository for the {@code sync_watermarks} single-row table. The fixed id
 * {@code 00000000-0000-0000-0000-000000000001} is guarded by a CHECK constraint,
 * so {@code save()} always triggers an upsert.
 */
public interface SyncWatermarkRepository extends JpaRepository<SyncWatermarkEntity, UUID> {

  UUID FIXED_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  default Optional<String> findLastSheetNo() {
    return findById(FIXED_ID).map(SyncWatermarkEntity::getLastSheetNo);
  }
}