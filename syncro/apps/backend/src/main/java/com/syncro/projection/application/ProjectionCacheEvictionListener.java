package com.syncro.projection.application;

import com.syncro.projection.infrastructure.ProjectionRedisCache;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The projection module owns eviction for its cache: mutators in shift-config, sparepart
 * installation, procurement, and telemetry persistence only publish
 * {@link ProjectionCacheEvictionEvent} (spring-context dependency), never call this module.
 *
 * <p>Eviction runs AFTER_COMMIT so a concurrent GET cannot recompute from pre-commit state and
 * re-cache a stale view; {@code fallbackExecution} covers publishers without an active
 * transaction (telemetry persistence).
 */
@Component
public class ProjectionCacheEvictionListener {

  private final ProjectionRedisCache cache;

  public ProjectionCacheEvictionListener(ProjectionRedisCache cache) {
    this.cache = cache;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onEviction(ProjectionCacheEvictionEvent event) {
    if (event.machineId() == null) {
      cache.evictAll();
    } else {
      cache.evictMachine(event.machineId());
    }
  }
}
