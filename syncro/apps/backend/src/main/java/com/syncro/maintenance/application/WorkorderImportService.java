package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryRepository;
import com.syncro.authz.application.PolicyDecisionPoint;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single upsert entry point for SYNCED workorders (AD-7/FR-150/FR-151, story 13-1).
 *
 * <p>The sync module never writes {@code work_orders} via JPA — every external row
 * passes through {@link #upsert}. The maintenance module owns the workorder aggregate:
 * this service find-or-creates the entity with {@code source=SYNCED} and the external
 * {@code sheet_no} as id (no WO- prefix), writes a {@code SYNC}/{@code SYSTEM} status
 * history row, and records audit via {@link AuditLogWriter#recordSystem}. A re-sync of
 * an existing sheet_no updates fields and bumps {@code sync_version} — idempotent per
 * FR-151. Unresolvable machines/categories never auto-create workorders (13.3 adds
 * quarantine); callers resolve and skip such rows before invoking this service.
 */
@Service
public class WorkorderImportService {

  static final String SOURCE_SYNCED = "SYNCED";
  static final String HISTORY_SOURCE_SYNC = "SYNC";
  static final String SYSTEM_ACTOR = "SYSTEM";

  private final WorkOrderRepository workOrders;
  private final WorkOrderStatusHistoryRepository statusHistory;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public WorkorderImportService(WorkOrderRepository workOrders,
      WorkOrderStatusHistoryRepository statusHistory, AuditLogWriter auditLog, Clock clock) {
    this.workOrders = workOrders;
    this.statusHistory = statusHistory;
    this.auditLog = auditLog;
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
   * @return the workorder id (the sheet_no)
   */
  @Transactional
  public String upsert(String sheetNo, UUID machineId, UUID categoryId, WorkOrderStatus status,
      String description, String parentId, Instant externalCreatedAt, Instant externalUpdatedAt) {
    var now = Instant.now(clock);
    var existing = workOrders.findById(sheetNo);

    if (existing.isPresent()) {
      var entity = existing.get();
      var fromStatus = entity.getStatus();
      // External master fields (status/machine/category/description/parent) take the external
      // value only when the external row is newer than the last local touch; local operational
      // fields (report, evidence, ratings, sessions) are always preserved (13.2 adds field
      // classification). sync_version bumps with every applied update. A no-op re-sync (stale
      // or equal external row) writes nothing — no history, no audit — so unchanged workorders
      // do not grow the audit/status-history tables on every 60s poll.
      if (externalUpdatedAt == null || !externalUpdatedAt.isAfter(entity.getUpdatedAt())) {
        return sheetNo;
      }
      var previous = auditValues(entity);
      entity.applySync(status, categoryId, machineId, description, parentId, externalUpdatedAt);
      entity.bumpSyncVersion();
      var saved = workOrders.saveAndFlush(entity);
      statusHistory.saveAndFlush(historyRow(sheetNo, fromStatus, status, now));
      auditLog.recordSystem(new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ORDER,
          auditEntityId(sheetNo), sheetNo, null, previous, auditValues(saved), null));
      return saved.getId();
    }

    var created = new WorkOrderEntity(sheetNo, SOURCE_SYNCED, parentId, status, categoryId, machineId,
        description, 0L, null, null, null,
        externalCreatedAt != null ? externalCreatedAt : now,
        externalUpdatedAt != null ? externalUpdatedAt : now);
    var saved = workOrders.saveAndFlush(created);
    statusHistory.saveAndFlush(historyRow(sheetNo, null, status, now));
    auditLog.recordSystem(new AuditRecord(AuditAction.CREATE, AuditEntityType.WORK_ORDER,
        auditEntityId(sheetNo), sheetNo, null, null, auditValues(saved), null));
    return saved.getId();
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