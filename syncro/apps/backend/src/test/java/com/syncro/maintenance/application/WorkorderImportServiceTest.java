package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryRepository;
import com.syncro.sync.application.FieldClassificationService;
import com.syncro.sync.domain.UpsertResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 13-1/13-2 unit tests for {@link WorkorderImportService} — the single upsert entry
 * point for EXTERNAL workorders (FR-150/FR-151, FR-152/NFR-P2-9). Covers create, update,
 * idempotent re-sync, terminal-state protection, PENDING_SPAREPART preservation, and field
 * classification.
 */
@ExtendWith(MockitoExtension.class)
class WorkorderImportServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private WorkOrderStatusHistoryRepository statusHistory;
  @Mock
  private AuditLogWriter auditLog;
  @Mock
  private FieldClassificationService fieldClassification;
  @Mock
  private org.springframework.context.ApplicationEventPublisher events;

  private WorkorderImportService service;
  private final UUID machineId = UUID.randomUUID();
  private final UUID categoryId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    // Default: all fields are MASTER (AD-8 default for unmapped). Lenient because the
    // protection-gate tests return before isMaster is ever consulted.
    lenient().when(fieldClassification.isMaster("status")).thenReturn(true);
    lenient().when(fieldClassification.isMaster("machine_id")).thenReturn(true);
    lenient().when(fieldClassification.isMaster("category_id")).thenReturn(true);
    lenient().when(fieldClassification.isMaster("parent_id")).thenReturn(true);
    lenient().when(fieldClassification.isMaster("description")).thenReturn(true);
    service = new WorkorderImportService(workOrders, statusHistory, auditLog,
        fieldClassification, events, CLOCK);
  }

  @Test
  @DisplayName("13.1-IMP-001 P0 create: new sheet_no creates a SYNCED workorder with the external id")
  void createNewWorkorder() {
    when(workOrders.findById("EXT-00001")).thenReturn(Optional.empty());
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.upsert("EXT-00001", machineId, categoryId, WorkOrderStatus.OPEN,
        "machine breakdown", null, NOW, NOW);

    assertThat(result.action()).isEqualTo(UpsertResult.Action.CREATED);
    var captor = ArgumentCaptor.forClass(WorkOrderEntity.class);
    verify(workOrders).saveAndFlush(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getId()).isEqualTo("EXT-00001");
    assertThat(saved.getSource()).isEqualTo("EXTERNAL");
    assertThat(saved.getStatus()).isEqualTo(WorkOrderStatus.OPEN);
    assertThat(saved.getMachineId()).isEqualTo(machineId);
    assertThat(saved.getCategoryId()).isEqualTo(categoryId);
    assertThat(saved.getSyncVersion()).isZero();

    // History row: source SYNC, actor SYSTEM, from null → OPEN.
    var historyCaptor = ArgumentCaptor.forClass(WorkOrderStatusHistoryEntity.class);
    verify(statusHistory).saveAndFlush(historyCaptor.capture());
    var history = historyCaptor.getValue();
    assertThat(history.getWorkOrderId()).isEqualTo("EXT-00001");
    assertThat(history.getFromStatus()).isNull();
    assertThat(history.getToStatus()).isEqualTo("OPEN");
    assertThat(history.getSource()).isEqualTo("SYNC");
    assertThat(history.getActor()).isEqualTo("SYSTEM");

    // Audit: system CREATE on WORK_ORDER.
    var auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).recordSystem(auditCaptor.capture());
    var audit = auditCaptor.getValue();
    assertThat(audit.action()).isEqualTo(AuditAction.CREATE);
    assertThat(audit.entityType()).isEqualTo(AuditEntityType.WORK_ORDER);
    assertThat(audit.entityLabel()).isEqualTo("EXT-00001");

    // Story 20-1 (AD-6/AD-20): sync create invalidates analytics via WorkorderSyncedEvent.
    var eventCaptor = ArgumentCaptor.forClass(WorkorderSyncedEvent.class);
    verify(events).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().workOrderId()).isEqualTo("EXT-00001");
    assertThat(eventCaptor.getValue().machineId()).isEqualTo(machineId);
  }

  @Test
  @DisplayName("13.1-IMP-002 P0 update: existing sheet_no updates fields, never duplicates")
  void updateExistingWorkorder() {
    var existing = new WorkOrderEntity("EXT-00001", "EXTERNAL", null, WorkOrderStatus.OPEN,
        categoryId, machineId, "old description", 0L, null, null, null,
        NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    when(workOrders.findById("EXT-00001")).thenReturn(Optional.of(existing));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.upsert("EXT-00001", machineId, categoryId, WorkOrderStatus.IN_PROGRESS,
        "new description", "EXT-00000", NOW.minusSeconds(7200), NOW);

    assertThat(result.action()).isEqualTo(UpsertResult.Action.UPDATED);
    verify(workOrders, never()).save(any());

    var captor = ArgumentCaptor.forClass(WorkOrderEntity.class);
    verify(workOrders).saveAndFlush(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getStatus()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
    assertThat(saved.getDescription()).isEqualTo("new description");
    assertThat(saved.getParentId()).isEqualTo("EXT-00000");
    assertThat(saved.getSyncVersion()).isEqualTo(1L);
    // Machine binding takes the external value on re-sync (AD-7 master field).
    assertThat(saved.getMachineId()).isEqualTo(machineId);

    // History row: from OPEN → IN_PROGRESS, source SYNC.
    var historyCaptor = ArgumentCaptor.forClass(WorkOrderStatusHistoryEntity.class);
    verify(statusHistory).saveAndFlush(historyCaptor.capture());
    assertThat(historyCaptor.getValue().getFromStatus()).isEqualTo("OPEN");
    assertThat(historyCaptor.getValue().getToStatus()).isEqualTo("IN_PROGRESS");
    assertThat(historyCaptor.getValue().getSource()).isEqualTo("SYNC");

    // Audit: system UPDATE.
    var auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).recordSystem(auditCaptor.capture());
    assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.UPDATE);
    assertThat(auditCaptor.getValue().previousValue()).containsEntry("status", "OPEN");
    assertThat(auditCaptor.getValue().newValue()).containsEntry("status", "IN_PROGRESS");

    // Story 20-1: sync update also invalidates analytics.
    verify(events).publishEvent(any(WorkorderSyncedEvent.class));
  }

  @Test
  @DisplayName("13.1-IMP-003 P0 idempotent re-sync: stale external row never regresses local state, no writes")
  void idempotentResyncStaleExternal() {
    var existing = new WorkOrderEntity("EXT-00001", "EXTERNAL", null, WorkOrderStatus.OPEN,
        categoryId, machineId, "description", 3L, null, null, null,
        NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    when(workOrders.findById("EXT-00001")).thenReturn(Optional.of(existing));

    // Stale external row (older than local updated_at): no-op — nothing is persisted,
    // no history row, no audit.
    var result = service.upsert("EXT-00001", machineId, categoryId, WorkOrderStatus.CLOSED,
        "should-not-overwrite", null, NOW.minusSeconds(7200), NOW.minusSeconds(7200));

    assertThat(result.action()).isEqualTo(UpsertResult.Action.UPDATED);
    verify(workOrders, never()).saveAndFlush(any());
    verify(statusHistory, never()).saveAndFlush(any());
    verify(auditLog, never()).recordSystem(any());
    // Story 20-1: a stale no-op must not invalidate analytics.
    verify(events, never()).publishEvent(any());
  }

  @Test
  @DisplayName("13.1-IMP-004 P0 idempotent re-sync: newer external row updates fields and bumps sync_version")
  void idempotentResyncNewerExternal() {
    var existing = new WorkOrderEntity("EXT-00001", "EXTERNAL", null, WorkOrderStatus.OPEN,
        categoryId, machineId, "description", 3L, null, null, null,
        NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    when(workOrders.findById("EXT-00001")).thenReturn(Optional.of(existing));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.upsert("EXT-00001", machineId, categoryId, WorkOrderStatus.IN_PROGRESS,
        "updated description", null, NOW.minusSeconds(7200), NOW.minusSeconds(1800));

    assertThat(result.action()).isEqualTo(UpsertResult.Action.UPDATED);
    var captor = ArgumentCaptor.forClass(WorkOrderEntity.class);
    verify(workOrders).saveAndFlush(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getStatus()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
    assertThat(saved.getDescription()).isEqualTo("updated description");
    assertThat(saved.getSyncVersion()).isEqualTo(4L);
  }

  // =========================================================================
  // Story 13-2: Conflict resolution & field classification
  // =========================================================================

  @Test
  @DisplayName("13.2-IMP-005 P0 terminal-state protection: PENDING_REVIEW is non-terminal, newer row updates it")
  void pendingReviewIsNotTerminal() {
    var existing = new WorkOrderEntity("EXT-00001", "EXTERNAL", null, WorkOrderStatus.PENDING_REVIEW,
        categoryId, machineId, "done", 5L, null, null, null,
        NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    when(workOrders.findById("EXT-00001")).thenReturn(Optional.of(existing));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    // PENDING_REVIEW replaced DONE as the completion state and is NOT terminal under the
    // 6-value set — only CLOSED/CANCELLED are. A newer external row updates it.
    var result = service.upsert("EXT-00001", machineId, categoryId, WorkOrderStatus.CLOSED,
        "approved", null, NOW.minusSeconds(7200), NOW);

    assertThat(result.action()).isEqualTo(UpsertResult.Action.UPDATED);
    var captor = ArgumentCaptor.forClass(WorkOrderEntity.class);
    verify(workOrders).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(WorkOrderStatus.CLOSED);
  }

  @Test
  @DisplayName("13.2-IMP-006 P0 terminal-state protection: CLOSED workorder is never regressed, quarantined")
  void terminalStateProtectionClosed() {
    var existing = new WorkOrderEntity("EXT-00001", "EXTERNAL", null, WorkOrderStatus.CLOSED,
        categoryId, machineId, "closed", 5L, null, null, null,
        NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    when(workOrders.findById("EXT-00001")).thenReturn(Optional.of(existing));

    var result = service.upsert("EXT-00001", machineId, categoryId, WorkOrderStatus.OPEN,
        "should-not-reopen", null, NOW.minusSeconds(7200), NOW);

    assertThat(result.action()).isEqualTo(UpsertResult.Action.REJECTED);
    assertThat(result.reason()).isEqualTo(UpsertResult.TERMINAL_STATE_PROTECTED);
    verify(workOrders, never()).saveAndFlush(any());
    verify(statusHistory, never()).saveAndFlush(any());
    verify(auditLog, never()).recordSystem(any());
  }

  @Test
  @DisplayName("13.2-IMP-007 P0 PENDING_SPAREPART preservation: external status does not override derived state")
  void onProcurementPreservation() {
    var existing = new WorkOrderEntity("EXT-00001", "EXTERNAL", null, WorkOrderStatus.PENDING_SPAREPART,
        categoryId, machineId, "waiting for parts", 2L, null, null, null,
        NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    when(workOrders.findById("EXT-00001")).thenReturn(Optional.of(existing));

    // External tries to override to IN_PROGRESS or CLOSED — rejected.
    var result = service.upsert("EXT-00001", machineId, categoryId, WorkOrderStatus.IN_PROGRESS,
        "should-not-override", null, NOW.minusSeconds(7200), NOW);

    assertThat(result.action()).isEqualTo(UpsertResult.Action.REJECTED);
    assertThat(result.reason()).isEqualTo(UpsertResult.ON_PROCUREMENT_PROTECTED);
    verify(workOrders, never()).saveAndFlush(any());
    verify(statusHistory, never()).saveAndFlush(any());
    verify(auditLog, never()).recordSystem(any());
  }

  @Test
  @DisplayName("13.2-IMP-008 P0 PENDING_SPAREPART: external PENDING_SPAREPART is allowed to proceed")
  void onProcurementToOnProcurementAllowed() {
    var existing = new WorkOrderEntity("EXT-00001", "EXTERNAL", null, WorkOrderStatus.PENDING_SPAREPART,
        categoryId, machineId, "waiting for parts", 2L, null, null, null,
        NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    when(workOrders.findById("EXT-00001")).thenReturn(Optional.of(existing));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    // External says ON_PROCUREMENT too — same status, but other master fields may change.
    var result = service.upsert("EXT-00001", machineId, categoryId, WorkOrderStatus.PENDING_SPAREPART,
        "updated description", null, NOW.minusSeconds(7200), NOW);

    assertThat(result.action()).isEqualTo(UpsertResult.Action.UPDATED);
    // status stayed PENDING_SPAREPART, description updated (MASTER field).
    var captor = ArgumentCaptor.forClass(WorkOrderEntity.class);
    verify(workOrders).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getDescription()).isEqualTo("updated description");
    assertThat(captor.getValue().getStatus()).isEqualTo(WorkOrderStatus.PENDING_SPAREPART);
  }

  @Test
  @DisplayName("13.2-IMP-009 P0 field classification: OPERATIONAL fields are preserved on update")
  void operationalFieldsPreserved() {
    // Override field classification: status=MASTER, others=OPERATIONAL.
    // (isMaster for status is already true from setUp)
    when(fieldClassification.isMaster("machine_id")).thenReturn(false);
    when(fieldClassification.isMaster("category_id")).thenReturn(false);
    when(fieldClassification.isMaster("description")).thenReturn(false);
    when(fieldClassification.isMaster("parent_id")).thenReturn(false);

    var existing = new WorkOrderEntity("EXT-00001", "EXTERNAL", null, WorkOrderStatus.IN_PROGRESS,
        categoryId, machineId, "local description", 2L, null, null, null,
        NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    when(workOrders.findById("EXT-00001")).thenReturn(Optional.of(existing));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var newMachineId = UUID.randomUUID();
    var newCategoryId = UUID.randomUUID();

    var result = service.upsert("EXT-00001", newMachineId, newCategoryId, WorkOrderStatus.PENDING_REVIEW,
        "external description", "EXT-PARENT", NOW.minusSeconds(7200), NOW);

    assertThat(result.action()).isEqualTo(UpsertResult.Action.UPDATED);
    var captor = ArgumentCaptor.forClass(WorkOrderEntity.class);
    verify(workOrders).saveAndFlush(captor.capture());
    var saved = captor.getValue();
    // Status is MASTER — applied.
    assertThat(saved.getStatus()).isEqualTo(WorkOrderStatus.PENDING_REVIEW);
    // All other fields are OPERATIONAL — preserved from local entity.
    assertThat(saved.getMachineId()).isEqualTo(machineId);
    assertThat(saved.getCategoryId()).isEqualTo(categoryId);
    assertThat(saved.getDescription()).isEqualTo("local description");
    // parentId was null on existing; OPERATIONAL means it keeps the null.
    assertThat(saved.getParentId()).isNull();
  }
}