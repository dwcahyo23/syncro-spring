package com.syncro.maintenance.infrastructure.db;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/**
 * Workorder persistence (10.2). {@code findByIdempotencyKeyAndCreatedByAndCreatedAtAfter}
 * backs the 5-minute Idempotency-Key dedupe (AD-3): a replay within the window finds the
 * existing row scoped to the same creator, and the window is enforced by the timestamp
 * predicate alone — no purge job. The {@code uq_work_orders_idempotency_key} partial
 * unique index (V48) prevents concurrent same-key inserts.
 *
 * <p>The 10.3 locking finders take {@code SELECT ... FOR UPDATE} on the workorder row and
 * its children: parent close (FR-120/AD-3) locks child statuses in the same transaction,
 * and procurement derivation (AD-5) serializes per-workorder recomputes.
 */
public interface WorkOrderRepository extends JpaRepository<WorkOrderEntity, String> {

  Optional<WorkOrderEntity> findByIdempotencyKeyAndCreatedByAndCreatedAtAfter(
      String idempotencyKey, UUID createdBy, Instant createdAt);

  /** Children of a workorder, locked for update — parent close reads child statuses. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select child from WorkOrderEntity child where child.parentId = :parentId")
  List<WorkOrderEntity> findByParentIdForUpdate(@Param("parentId") String parentId);

  /** A single workorder row locked for update — serializes derived recomputes (AD-5). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select workOrder from WorkOrderEntity workOrder where workOrder.id = :id")
  Optional<WorkOrderEntity> findByIdForUpdate(@Param("id") String id);
}
