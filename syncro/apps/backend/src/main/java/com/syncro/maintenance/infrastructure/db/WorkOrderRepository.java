package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import java.time.Instant;
import java.util.Collection;
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

  boolean existsByPreventiveScheduleId(java.util.UUID preventiveScheduleId);

  Optional<WorkOrderEntity> findByPreventiveScheduleId(java.util.UUID preventiveScheduleId);

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

  /**
   * Story 10-7 single-query kanban read (FR-119): every non-terminal workorder visible
   * in the derived scope (plant OR machine-group) LEFT JOINed with its todos, ordered by
   * status/workorder id so a workorder's rows are contiguous. The flat rows are grouped
   * into status → workorder items by the application layer — this avoids N+1 on the board.
   * {@code unrestricted} (SUPER_ADMIN — derived scope plantIds is null) bypasses the scope
   * filter; the plant/group lists are ignored when true.
   */
  @Query("""
      select new com.syncro.maintenance.infrastructure.db.WorkOrderKanbanRow(w, c, t)
      from WorkOrderEntity w
      left join WorkOrderCategoryEntity c on c.id = w.categoryId
      left join WorkOrderTodoEntity t on t.workorderId = w.id
      join MachineEntity m on m.id = w.machineId
      where w.status not in :terminalStatuses
        and (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
      order by w.status, w.id, t.sortOrder
      """)
  List<WorkOrderKanbanRow> findKanbanRows(
      @Param("terminalStatuses") Collection<WorkOrderStatus> terminalStatuses,
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds);

  /**
   * Story 10-8 single-query ratings-page read (FR-121/FR-124): every CLOSED workorder
   * visible in the derived scope (plant OR machine-group), LEFT JOINed with its category.
   * Mirrors {@link #findKanbanRows} (10.7) but filters on {@code status = CLOSED} — the
   * ratings page reuses the closed-workorder query, not a full list view (10-6 deferral).
   * {@code unrestricted} (SUPER_ADMIN) bypasses the scope filter.
   */
  @Query("""
      select new com.syncro.maintenance.infrastructure.db.WorkOrderRatingRow(w, c)
      from WorkOrderEntity w
      left join WorkOrderCategoryEntity c on c.id = w.categoryId
      join MachineEntity m on m.id = w.machineId
      where w.status = com.syncro.maintenance.domain.workorder.WorkOrderStatus.CLOSED
        and (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
      order by w.id
      """)
  List<WorkOrderRatingRow> findClosedForRating(
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds);

  /**
   * Story 10-8 executor-pool read (FR-121): the users who can be rated as technicians on
   * a workorder — the workorder's assigned technician plus every user who logged a repair
   * session on it. One query, no N+1 (design note).
   */
  @Query("""
      select distinct r.technicianId
      from RepairSessionEntity r
      where r.workOrderId = :workOrderId and r.technicianId is not null
      """)
  List<UUID> findSessionTechnicianIds(@Param("workOrderId") String workOrderId);
}
