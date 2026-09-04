package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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

  /**
   * Story 14-4 (FR-181): all INTERNAL workorders in the given status — used by the ack
   * worker to find ack candidates. EXTERNAL workorders are excluded (internal-only events).
   */
  @Query("select w from WorkOrderEntity w where w.source = 'INTERNAL' and w.status = :status")
  List<WorkOrderEntity> findAllNonSyncedByStatus(@Param("status") WorkOrderStatus status);

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
   * (CLOSED remains the ratings gate under the 6-value status set — story 15-1.)
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

  /**
   * Story workorder-table server-paginated list read: every workorder visible in the
   * derived scope (plant OR machine-group) LEFT JOINed with its category, machine and
   * plant — no todos, so no cartesian blowup on hundreds of rows. Optional filters:
   * {@code from}/{@code to} are inclusive ISO dates matched against {@code createdAt}
   * (server clock UTC — boundary = start-of-day/end-of-day), {@code status} is a single
   * status, {@code machineId} narrows to one machine, and {@code search} matches the
   * workorder id, machine code/name or category label (case-insensitive, {@code %term%}
   * escaped). Rows are ordered by {@code createdAt desc}, then workorder id for a stable
   * page order. {@code unrestricted} (SUPER_ADMIN — derived scope plantIds is null)
   * bypasses the scope filter; the plant/group lists are ignored when true.
   */
  @Query("""
      select new com.syncro.maintenance.infrastructure.db.WorkOrderListRow(
        w, c, m.code, m.name, m.plant.code)
      from WorkOrderEntity w
      left join WorkOrderCategoryEntity c on c.id = w.categoryId
      join MachineEntity m on m.id = w.machineId
      where (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
        and w.createdAt >= :from
        and w.createdAt < :to
        and (:status = '' or w.status = cast(:status as string))
        and (:machineId = :noMachine or w.machineId = :machineId)
        and (:categoryCode = '' or c.code = :categoryCode)
        and (:search = ''
             or lower(w.id) like :search escape '\\'
             or lower(m.code) like :search escape '\\'
             or lower(m.name) like :search escape '\\'
             or lower(c.label) like :search escape '\\')
      order by w.createdAt desc, w.id
      """)
  List<WorkOrderListRow> findScopedPage(
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("status") String status,
      @Param("machineId") UUID machineId,
      @Param("noMachine") UUID noMachine,
      @Param("categoryCode") String categoryCode,
      @Param("search") String search,
      Pageable pageable);

  /**
   * Matching-count twin of {@link #findScopedPage}: identical predicates (without the
   * page order/limit) so {@code total} reflects the same filtered set.
   */
  @Query("""
      select count(w)
      from WorkOrderEntity w
      left join WorkOrderCategoryEntity c on c.id = w.categoryId
      join MachineEntity m on m.id = w.machineId
      where (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
        and w.createdAt >= :from
        and w.createdAt < :to
        and (:status = '' or w.status = cast(:status as string))
        and (:machineId = :noMachine or w.machineId = :machineId)
        and (:categoryCode = '' or c.code = :categoryCode)
        and (:search = ''
             or lower(w.id) like :search escape '\\'
             or lower(m.code) like :search escape '\\'
             or lower(m.name) like :search escape '\\'
             or lower(c.label) like :search escape '\\')
      """)
  long countScoped(
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("status") String status,
      @Param("machineId") UUID machineId,
      @Param("noMachine") UUID noMachine,
      @Param("categoryCode") String categoryCode,
      @Param("search") String search);

  // -------------------------------------------------------------------------
  // Dashboard group-by counts (14-1, FR-171)
  // -------------------------------------------------------------------------

  /**
   * Scoped count of workorders grouped by status. Optional filters: sectionId,
   * status, categoryCode. Follows the same (unrestricted, plantIds, groupIds)
   * scope pattern as {@link #findScopedPage}.
   */
  @Query("""
      select w.status as status, count(w) as count
      from WorkOrderEntity w
      left join WorkOrderCategoryEntity c on c.id = w.categoryId
      join MachineEntity m on m.id = w.machineId
      where (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
        and (:plantId = :noPlant or m.plant.id = :plantId)
        and (:sectionId = :noSectionId or m.machineGroup.sectionId = :sectionId)
        and (:status = '' or w.status = cast(:status as string))
        and (:categoryCode = '' or c.code = :categoryCode)
      group by w.status
      """)
  List<StatusCountProjection> countByStatusScoped(
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds,
      @Param("plantId") UUID plantId,
      @Param("noPlant") UUID noPlant,
      @Param("sectionId") UUID sectionId,
      @Param("noSectionId") UUID noSectionId,
      @Param("status") String status,
      @Param("categoryCode") String categoryCode);

  /**
   * Scoped count of workorders grouped by category. Categories with no workorders
   * are absent; the frontend renders zero by omission.
   */
  @Query("""
      select c.id as categoryId, c.code as categoryCode, c.label as categoryLabel, count(w) as count
      from WorkOrderEntity w
      left join WorkOrderCategoryEntity c on c.id = w.categoryId
      join MachineEntity m on m.id = w.machineId
      where (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
        and (:plantId = :noPlant or m.plant.id = :plantId)
        and (:sectionId = :noSectionId or m.machineGroup.sectionId = :sectionId)
        and (:status = '' or w.status = cast(:status as string))
        and (:categoryCode = '' or c.code = :categoryCode)
      group by c.id, c.code, c.label
      order by count(w) desc
      """)
  List<CategoryCountProjection> countByCategoryScoped(
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds,
      @Param("plantId") UUID plantId,
      @Param("noPlant") UUID noPlant,
      @Param("sectionId") UUID sectionId,
      @Param("noSectionId") UUID noSectionId,
      @Param("status") String status,
      @Param("categoryCode") String categoryCode);

  /**
   * Scoped count of non-terminal workorders grouped by machine — open workorder
   * counts for the machine dashboard.
   */
  @Query("""
      select w.machineId as machineId, count(w) as count
      from WorkOrderEntity w
      join MachineEntity m on m.id = w.machineId
      where w.status not in :terminalStatuses
        and (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
      group by w.machineId
      """)
  List<OpenByMachineProjection> countOpenByMachineScoped(
      @Param("terminalStatuses") Collection<WorkOrderStatus> terminalStatuses,
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds);

  /**
   * DW-148: per-month open/close counts for the workorder dashboard's Jan–Dec bar chart.
   * Open = non-terminal statuses, Close = terminal statuses (DONE/CLOSED/CANCELLED),
   * bucketed by the workorder's {@code createdAt} month within the given year range.
   */
  @Query("""
      select month(w.createdAt) as month, w.status as status, count(w) as count
      from WorkOrderEntity w
      left join WorkOrderCategoryEntity c on c.id = w.categoryId
      join MachineEntity m on m.id = w.machineId
      where (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
        and (:plantId = :noPlant or m.plant.id = :plantId)
        and (:sectionId = :noSectionId or m.machineGroup.sectionId = :sectionId)
        and (:status = '' or w.status = cast(:status as string))
        and (:categoryCode = '' or c.code = :categoryCode)
        and w.createdAt >= :from
        and w.createdAt < :to
      group by month(w.createdAt), w.status
      """)
  List<MonthlyStatusCountProjection> countMonthlyByStatusScoped(
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds,
      @Param("plantId") UUID plantId,
      @Param("noPlant") UUID noPlant,
      @Param("sectionId") UUID sectionId,
      @Param("noSectionId") UUID noSectionId,
      @Param("status") String status,
      @Param("categoryCode") String categoryCode,
      @Param("from") Instant from,
      @Param("to") Instant to);

  // -------------------------------------------------------------------------
  // Dashboard analytics (14-2, FR-173/FR-174)
  // -------------------------------------------------------------------------

  /**
   * Stopped breakdown workorders (category code {@code 01}, status PENDING_REVIEW or
   * CLOSED) in scope within the rolling analytics window. The window predicate is keyed on
   * the DERIVED stop time — the latest PENDING_REVIEW transition from
   * {@code work_order_status_history} (fallback {@code updatedAt} when no PENDING_REVIEW
   * row exists, the sync edge case) — matching the ordering key
   * ({@code coalesce(max(h.transitionedAt), w.updatedAt)}). The LEFT JOIN + GROUP BY
   * derives the stop time in SQL so the projection, the HAVING window predicate and the
   * ORDER BY all use the same key. OPEN/IN_PROGRESS/PENDING_SPAREPART breakdown workorders
   * are never counted as stops. {@code from}/{@code to} are the 30-day window
   * bounds computed from the injected {@code Clock}. Category target is nullable —
   * on-time % only counts rows where BOTH responseTimeMinutes and targetResponseMinutes
   * are present.
   */
  @Query("""
      select new com.syncro.maintenance.infrastructure.db.WorkOrderAnalyticsRow(
        w.id, w.status, w.machineId, m.plant.id, w.assignedTechnicianId,
        w.mttrMinutes, w.responseTimeMinutes, c.code, c.targetResponseMinutes,
        coalesce(max(h.transitionedAt), w.updatedAt))
      from WorkOrderEntity w
      left join WorkOrderCategoryEntity c on c.id = w.categoryId
      join MachineEntity m on m.id = w.machineId
      left join WorkOrderStatusHistoryEntity h on h.workOrderId = w.id and h.toStatus = 'PENDING_REVIEW'
      where c.code = :breakdownCode
        and w.status in :stoppedStatuses
        and (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
      group by w.id, w.status, w.machineId, m.plant.id, w.assignedTechnicianId,
        w.mttrMinutes, w.responseTimeMinutes, c.code, c.targetResponseMinutes, w.updatedAt
      having coalesce(max(h.transitionedAt), w.updatedAt) >= :from
        and coalesce(max(h.transitionedAt), w.updatedAt) < :to
      """)
  List<WorkOrderAnalyticsRow> findStoppedBreakdownAnalyticsRows(
      @Param("breakdownCode") String breakdownCode,
      @Param("stoppedStatuses") Collection<WorkOrderStatus> stoppedStatuses,
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /**
   * Technician objective-KPI rows (FR-174): every workorder in scope with its
   * assigned technician and category target. Window keyed on updatedAt (the
   * documented stop-time source for attribution — the workorder's last update).
   * Rows are deduplicated with the repair-session source by (technician, workorder)
   * in the application layer.
   */
  @Query("""
      select new com.syncro.maintenance.infrastructure.db.TechnicianKpiRow(
        w.assignedTechnicianId, w.id, w.status, m.plant.id, c.code,
        w.mttrMinutes, w.responseTimeMinutes, c.targetResponseMinutes)
      from WorkOrderEntity w
      left join WorkOrderCategoryEntity c on c.id = w.categoryId
      join MachineEntity m on m.id = w.machineId
      where w.assignedTechnicianId is not null
        and (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
        and w.updatedAt >= :from
        and w.updatedAt < :to
      """)
  List<TechnicianKpiRow> findTechnicianObjectiveRows(
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /**
   * Technician objective-KPI rows sourced from repair sessions (FR-174): a workorder is
   * attributed to every technician who logged a completed repair session on it, even
   * when the assigned technician is null (a WO can be worked by more than one person).
   */
  @Query("""
      select new com.syncro.maintenance.infrastructure.db.TechnicianKpiRow(
        r.technicianId, w.id, w.status, m.plant.id, c.code,
        w.mttrMinutes, w.responseTimeMinutes, c.targetResponseMinutes)
      from RepairSessionEntity r
      join WorkOrderEntity w on w.id = r.workOrderId
      left join WorkOrderCategoryEntity c on c.id = w.categoryId
      join MachineEntity m on m.id = w.machineId
      where r.endedAt is not null
        and (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
        and w.updatedAt >= :from
        and w.updatedAt < :to
      """)
  List<TechnicianKpiRow> findTechnicianObjectiveRowsFromSessions(
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds,
      @Param("from") Instant from,
      @Param("to") Instant to);

  // -------------------------------------------------------------------------
  // KPI materialization source reads (20-1, AD-6/AD-20)
  // -------------------------------------------------------------------------

  /**
   * Story 20-1 (AD-6): stopped breakdown workorders (category code {@code 01}, status
   * CLOSED) with the DERIVED {@code woStopAt} — the latest PENDING_REVIEW transition
   * from {@code work_order_status_history}, {@code updatedAt} fallback (the sync edge
   * case), exactly the 14-2 key. The month window is applied by the adapter so the
   * derived stop time and the filter share one expression. Ordering is NOT guaranteed —
   * MTBF consumers must sort by {@code woStopAt}, never by id (the reference bug).
   */
  @Query("""
      select new com.syncro.maintenance.infrastructure.db.KpiSourceRows$BreakdownStopRow(
        w.id, w.machineId, m.plant.id, coalesce(max(h.transitionedAt), w.updatedAt))
      from WorkOrderEntity w
      join WorkOrderCategoryEntity c on c.id = w.categoryId
      join MachineEntity m on m.id = w.machineId
      left join WorkOrderStatusHistoryEntity h on h.workOrderId = w.id and h.toStatus = 'PENDING_REVIEW'
      where c.code = :breakdownCode and w.status = :closedStatus
      group by w.id, w.machineId, m.plant.id, w.updatedAt
      """)
  List<KpiSourceRows.BreakdownStopRow> findBreakdownStops(
      @Param("breakdownCode") String breakdownCode,
      @Param("closedStatus") WorkOrderStatus closedStatus);

  // -------------------------------------------------------------------------
  // Projection interfaces for group-by results
  // -------------------------------------------------------------------------

  interface StatusCountProjection {
    WorkOrderStatus getStatus();

    long getCount();
  }

  interface CategoryCountProjection {
    UUID getCategoryId();

    String getCategoryCode();

    String getCategoryLabel();

    long getCount();
  }

  interface OpenByMachineProjection {
    UUID getMachineId();

    long getCount();
  }

  interface MonthlyStatusCountProjection {
    int getMonth();

    WorkOrderStatus getStatus();

    long getCount();
  }
}
