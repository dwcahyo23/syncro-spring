package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
 * Story 13-1 unit tests for {@link WorkorderImportService} — the single upsert entry
 * point for SYNCED workorders (FR-150/FR-151). Create, update (never duplicates),
 * and idempotent re-sync semantics.
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

  private WorkorderImportService service;
  private final UUID machineId = UUID.randomUUID();
  private final UUID categoryId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new WorkorderImportService(workOrders, statusHistory, auditLog, CLOCK);
  }

  @Test
  @DisplayName("13.1-IMP-001 P0 create: new sheet_no creates a SYNCED workorder with the external id")
  void createNewWorkorder() {
    when(workOrders.findById("EXT-00001")).thenReturn(Optional.empty());
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var id = service.upsert("EXT-00001", machineId, categoryId, WorkOrderStatus.OPEN,
        "machine breakdown", null, NOW, NOW);

    assertThat(id).isEqualTo("EXT-00001");
    var captor = ArgumentCaptor.forClass(WorkOrderEntity.class);
    verify(workOrders).saveAndFlush(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getId()).isEqualTo("EXT-00001");
    assertThat(saved.getSource()).isEqualTo("SYNCED");
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
  }

  @Test
  @DisplayName("13.1-IMP-002 P0 update: existing sheet_no updates fields, never duplicates")
  void updateExistingWorkorder() {
    var existing = new WorkOrderEntity("EXT-00001", "SYNCED", null, WorkOrderStatus.OPEN,
        categoryId, machineId, "old description", 0L, null, null, null,
        NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    when(workOrders.findById("EXT-00001")).thenReturn(Optional.of(existing));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var id = service.upsert("EXT-00001", machineId, categoryId, WorkOrderStatus.IN_PROGRESS,
        "new description", "EXT-00000", NOW.minusSeconds(7200), NOW);

    assertThat(id).isEqualTo("EXT-00001");
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
  }

  @Test
  @DisplayName("13.1-IMP-003 P0 idempotent re-sync: stale external row never regresses local state, no writes")
  void idempotentResyncStaleExternal() {
    var existing = new WorkOrderEntity("EXT-00001", "SYNCED", null, WorkOrderStatus.OPEN,
        categoryId, machineId, "description", 3L, null, null, null,
        NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    when(workOrders.findById("EXT-00001")).thenReturn(Optional.of(existing));

    // Stale external row (older than local updated_at): no-op — nothing is persisted,
    // no history row, no audit. Unchanged workorders must not grow the audit tables on
    // every 60s poll.
    var id = service.upsert("EXT-00001", machineId, categoryId, WorkOrderStatus.CLOSED,
        "should-not-overwrite", null, NOW.minusSeconds(7200), NOW.minusSeconds(7200));

    assertThat(id).isEqualTo("EXT-00001");
    verify(workOrders, never()).saveAndFlush(any());
    verify(statusHistory, never()).saveAndFlush(any());
    verify(auditLog, never()).recordSystem(any());
  }

  @Test
  @DisplayName("13.1-IMP-004 P0 idempotent re-sync: newer external row updates fields and bumps sync_version")
  void idempotentResyncNewerExternal() {
    var existing = new WorkOrderEntity("EXT-00001", "SYNCED", null, WorkOrderStatus.OPEN,
        categoryId, machineId, "description", 3L, null, null, null,
        NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    when(workOrders.findById("EXT-00001")).thenReturn(Optional.of(existing));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.upsert("EXT-00001", machineId, categoryId, WorkOrderStatus.IN_PROGRESS,
        "updated description", null, NOW.minusSeconds(7200), NOW.minusSeconds(1800));

    var captor = ArgumentCaptor.forClass(WorkOrderEntity.class);
    verify(workOrders).saveAndFlush(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getStatus()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
    assertThat(saved.getDescription()).isEqualTo("updated description");
    assertThat(saved.getSyncVersion()).isEqualTo(4L);
  }
}