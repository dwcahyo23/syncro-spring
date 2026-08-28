package com.syncro.sync.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA repository for {@code sync_field_mappings} — field classification config (AD-8).
 * {@link com.syncro.sync.application.FieldClassificationService} loads the full table
 * into memory at startup; unmapped fields default to MASTER.
 */
public interface SyncFieldMappingRepository extends JpaRepository<SyncFieldMappingEntity, String> {
}