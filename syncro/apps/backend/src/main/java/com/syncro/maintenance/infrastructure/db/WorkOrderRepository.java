package com.syncro.maintenance.infrastructure.db;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Workorder persistence (10.2). {@code findByIdempotencyKeyAndCreatedByAndCreatedAtAfter}
 * backs the 5-minute Idempotency-Key dedupe (AD-3): a replay within the window finds the
 * existing row scoped to the same creator, and the window is enforced by the timestamp
 * predicate alone — no purge job. The {@code uq_work_orders_idempotency_key} partial
 * unique index (V48) prevents concurrent same-key inserts.
 */
public interface WorkOrderRepository extends JpaRepository<WorkOrderEntity, String> {

  Optional<WorkOrderEntity> findByIdempotencyKeyAndCreatedByAndCreatedAtAfter(
      String idempotencyKey, UUID createdBy, Instant createdAt);
}
