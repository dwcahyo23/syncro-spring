package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.authz.application.PolicyDecisionPoint;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryRepository;
import com.syncro.sync.application.FieldClassificationService;
import com.syncro.sync.domain.UpsertResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single upsert entry point for SYNCED workorders (AD-7/FR-150/FR-151, story 13-1/13-2).
 *
 * <p>The sync module never writes {@code work_orders} via JPA — every external row
 * passes through {@link #upsert}. The maintenance module owns the workorder aggregate:
 * this service find-or-creates the entity with {@code source=SYNCED} and the external
 * {@code sheet_no} as id (no WO- prefix), writes a {@code SYNC}/{@code SYSTEM} status
 * history row, and records audit via {@link AuditLogWriter#recordSystem}. A re-sync of
 * an existing sheet_no updates fields and bumps {@code sync_version} — idempotent per
 * FR-151.
 *
 * <p>Conflict resolution (story 13-2, AD-8/FR-152/NFR-P2-9): on the update path only
 * MASTER-classified fields take the external value (via {@link FieldClassificationService});
 * OPERATIONAL fields are never touched — the local report/evidence/ratings stay intact.
 * The freshness gate (13-1) runs first, so a stale external row is a no-op; only a
 * genuinely newer row can hit the protection gates: a DONE/CLOSED workorder is never
 * regressed ({@code TERMINAL_STATE_PROTECTED}) and the derived ON_PROCUREMENT state is
 * never overridden by an external status ({@code ON_PROCUREMENT_PROTECTED}). Rejections
 * are returned as {@link UpsertResult#REJECTED} — the sync batch processor persists them
 * to {@code sync_quarantine}; this service never writes quarantine itself.
 */
@Service
public class WorkorderImportService {

  static final String SOURCE_SYNCED = "SYNCED";
  static final String HISTORY_SOURCE_SYNC = "SYNC";
  static final String SYSTEM_ACTOR = "SYSTEM";

  private final WorkOrderRepository workOrders;
  private final WorkOrderStatusHistoryRepository statusHistory;
  private final AuditLogWriter auditLog;
  private final FieldClassificationService fieldClassification;
  private final Clock clock;

  public WorkorderImportService(WorkOrderRepository workOrders,
      WorkOrderStatusHistoryRepository statusHistory, AuditLogWriter auditLog,
      FieldClassificationService fieldClassification, Clock clock) {
    this.workOrders = workOrders;
    this.statusHistory = statusHistory;
    this.auditLog = auditLog;
    this.fieldClassification = fieldClassification;
    this.clock = clock;
  }

  /**
   * Creates or updates the workorder for the external {@code sheet_no}.
   *
   * @param sheetNo      external id (the workorder PK — no WO- prefix)
   * @param machineId    resolved machine UUID (must already exist)
   * @param categoryId   resolved category UUID (must already exist)
   * @param status       external status, mapped to the local lifecycle
   * @param description  free-text description
   * @param parentId     external parent sheet_no, or null
   * @param externalCreatedAt external row's created_at (UTC), used for the create path
   * @param externalUpdatedAt external row's updated_at (UTC), used for the freshness check
   * @return the upsert outcome ({@link UpsertResult}) — CREATED/UPDATED on success,
   *         REJECTED with the protection reason when a gate fires
   */
  @Transactional
  public UpsertResult upsert(String sheetNo, UUID machineId, UUID categoryId, WorkOrderStatus status,
      String description, String parentId, Instant externalCreatedAt, Instant externalUpdatedAt) {
    var now = Instant.now(clock);
    var existing = workOrders.findById(sheetNo);

    if (existing.isPresent()) {
      var entity = existing.get();
      var fromStatus = entity.getStatus();

      // Freshness gate (13-1): a stale or equal external row is a no-op — nothing is
      // persisted, no history, no audit. Guarding this first means a stale row never
      // triggers the protection gates either (no unbounded quarantine growth on every
      // 60s poll for terminal rows still present in the external feed).
      if (externalUpdatedAt == null || !externalUpdatedAt.isAfter(entity.getUpdatedAt())) {
        return UpsertResult.updated();
      }

      // Terminal-state protection (NFR-P2-9): a DONE/CLOSED workorder is never regressed
      // by sync. Any newer external touch of a terminal workorder is quarantined.
      if (fromStatus == WorkOrderStatus.DONE || fromStatus == WorkOrderStatus.CLOSED) {
        return UpsertResult.rejected(UpsertResult.TERMINAL_STATE_PROTECTED);
      }

      // ON_PROCUREMENT is derived locally from live sparepart requests (AD-5); an
      // external status never overrides it.
      if (fromStatus == WorkOrderStatus.ON_PROCUREMENT && status != WorkOrderStatus.ON_PROCUREMENT) {
        return UpsertResult.rejected(UpsertResult.ON_PROCUREMENT_PROTECTED);
      }

      // Field classification (AD-8): only MASTER fields take the external value. The
      // classification lives at the service layer — WorkOrderEntity.applySync remains a
      // plain bulk setter, and the service passes the effective (classified) values.
      var toStatus = fieldClassification.isMaster("status") ? status : fromStatus;
      var toCategoryId = fieldClassification.isMaster("category_id")
          ? categoryId : entity.getCategoryId();
      var toMachineId = fieldClassification.isMaster("machine_id")
          ? machineId : entity.getMachineId();
      var toDescription = fieldClassification.isMaster("description")
          ? description : entity.getDescription();
      // parent_id only changes when the external row provides one (13-1 fix) and the
      // field is MASTER-classified.
      var toParentId = parentId != null && fieldClassification.isMaster("parent_id")
          ? parentId : entity.getParentId();

      var previous = auditValues(entity);
      entity.applySync(toStatus, toCategoryId, toMachineId, toDescription, toParentId,
          externalUpdatedAt);
      entity.bumpSyncVersion();
      var saved = workOrders.saveAndFlush(entity);
      statusHistory.saveAndFlush(historyRow(sheetNo, fromStatus, toStatus, now));
      auditLog.recordSystem(new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ORDER,
          auditEntityId(sheetNo), sheetNo, null, previous, auditValues(saved), null));
      return UpsertResult.updated();
    }

    var created = new WorkOrderEntity(sheetNo, SOURCE_SYNCED, parentId, status, categoryId, machineId,
        description, 0L, null, null, null,
        externalCreatedAt != null ? externalCreatedAt : now,
        externalUpdatedAt != null ? externalUpdatedAt : now);
    var saved = workOrders.saveAndFlush(created);
    statusHistory.saveAndFlush(historyRow(sheetNo, null, status, now));
    auditLog.recordSystem(new AuditRecord(AuditAction.CREATE, AuditEntityType.WORK_ORDER,
        auditEntityId(sheetNo), sheetNo, null, null, auditValues(saved), null));
    return UpsertResult.created();
  }

  /** Status-history row for sync transitions (source SYNC, actor SYSTEM). */
  private WorkOrderStatusHistoryEntity historyRow(String workOrderId, WorkOrderStatus from,
      WorkOrderStatus to, Instant transitionedAt) {
    return new WorkOrderStatusHistoryEntity(UUID.randomUUID(), workOrderId,
        from != null ? from.name() : null, to.name(), HISTORY_SOURCE_SYNC, SYSTEM_ACTOR,
        traceId(), transitionedAt);
  }

  /** Trace id for history/audit correlation; falls back to a fresh UUID when none is stashed. */
  private String traceId() {
    return PolicyDecisionPoint.currentTraceId();
  }

  /** Workorder ids are VARCHAR PKs; audit needs a stable UUID per id for correlation. */
  private UUID auditEntityId(String workOrderId) {
    return UUID.nameUUIDFromBytes(workOrderId.getBytes(StandardCharsets.UTF_8));
  }

  private Map<String, Object> auditValues(WorkOrderEntity entity) {
    var values = new HashMap<String, Object>();
    values.put("id", entity.getId());
    values.put("source", entity.getSource());
    values.put("status", entity.getStatus().name());
    values.put("categoryId", entity.getCategoryId());
    values.put("machineId", entity.getMachineId());
    values.put("parentId", entity.getParentId());
    values.put("description", entity.getDescription());
    values.put("syncVersion", entity.getSyncVersion());
    return values;
  }
}
