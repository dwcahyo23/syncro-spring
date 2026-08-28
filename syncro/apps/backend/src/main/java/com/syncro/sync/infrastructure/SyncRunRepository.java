package com.syncro.sync.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA repository for {@code sync_runs} — per-cycle run records. The {@code save()}
 * method handles both insert (new run) and update (completion).
 */
public interface SyncRunRepository extends JpaRepository<SyncRunEntity, UUID> {

  /** Latest run by start time — backs the sync status view (story 13-3, FR-153). */
  Optional<SyncRunEntity> findTopByOrderByStartedAtDesc();
}