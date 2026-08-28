package com.syncro.sync.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA repository for {@code sync_quarantine} — append-only evidence for rejected
 * sync rows (story 13-2, NFR-P2-9). Quarantine rows are never deleted.
 */
public interface SyncQuarantineRepository extends JpaRepository<SyncQuarantineEntity, UUID> {
}