package com.syncro.sync.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.config.SyncProperties;
import com.syncro.maintenance.application.WorkorderImportService;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.sync.domain.BatchResult;
import com.syncro.sync.domain.SyncSourceRow;
import com.syncro.sync.domain.UpsertResult;
import com.syncro.sync.infrastructure.SyncQuarantineEntity;
import com.syncro.sync.infrastructure.SyncQuarantineRepository;
import com.syncro.sync.infrastructure.SyncWatermarkEntity;
import com.syncro.sync.infrastructure.SyncWatermarkRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
 * Story 13-1/13-2 unit tests for {@link SyncBatchProcessor} — the transactional batch
 * boundary between the worker and the import service. Covers the matrix rows the
 * worker/reader tests do not reach: unmapped machine/category skip, unknown-status
 * skip, the "batch fails → whole batch rolls back" contract, parent-close gate,
 * quarantine writes, and fallback category.
 */
@ExtendWith(MockitoExtension.class)
class SyncBatchProcessorTest {

  private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID MACHINE_ID = UUID.randomUUID();
  private static final UUID CATEGORY_ID = UUID.randomUUID();
  private static final UUID FALLBACK_CATEGORY_ID = UUID.randomUUID();

  @Mock
  private WorkorderImportService importService;
  @Mock
  private MachineRepository machines;
  @Mock
  private WorkOrderCategoryRepository categories;
  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private SyncWatermarkRepository watermarks;
  @Mock
  private SyncQuarantineRepository quarantine;
  @Mock
  private SyncProperties properties;

  private SyncBatchProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new SyncBatchProcessor(importService, machines, categories, workOrders,
        watermarks, quarantine, properties, CLOCK);
  }

  private SyncSourceRow row(String sheetNo, String machineCode, String categoryCode,
      String status) {
    return new SyncSourceRow(sheetNo, machineCode, categoryCode, status, "desc " + sheetNo,
        NOW, NOW, null);
  }

  private SyncSourceRow childRow(String sheetNo, String parentSheetNo) {
    return new SyncSourceRow(sheetNo, "MC-001", "01", "OPEN", "desc " + sheetNo,
        NOW, NOW, parentSheetNo);
  }

  private MachineEntity machine() {
    var m = org.mockito.Mockito.mock(MachineEntity.class);
    lenient().when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    lenient().when(m.getId()).thenReturn(MACHINE_ID);
    return m;
  }

  // =========================================================================
  // Story 13-1: existing behavior (unchanged semantics, BatchResult return)
  // =========================================================================

  @Test
  @DisplayName("13.1-BP-001 P0 unmapped machine: row skipped with warning, no upsert; watermark still advances")
  void unmappedMachineSkipsRow() {
    when(machines.findByCodeIgnoreCase("MC-UNKNOWN")).thenReturn(Optional.empty());

    var batch = List.of(row("EXT-00001", "MC-UNKNOWN", "01", "OPEN"));
    var result = processor.importBatch(batch);

    assertThat(result.upserted()).isZero();
    assertThat(result.rejected()).isZero();
    assertThat(result.entries()).isEmpty();
    verify(importService, never()).upsert(any(), any(), any(), any(), any(), any(), any(), any());
    // The watermark advances past the skipped row.
    var captor = ArgumentCaptor.forClass(SyncWatermarkEntity.class);
    verify(watermarks).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getLastSheetNo()).isEqualTo("EXT-00001");
  }

  @Test
  @DisplayName("13.1-BP-002 P0 unmapped category: row skipped with warning, no upsert; watermark advances")
  void unmappedCategorySkipsRow() {
    var m = machine();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(categories.findByCode("99")).thenReturn(Optional.empty());

    var batch = List.of(row("EXT-00001", "MC-001", "99", "OPEN"));
    var result = processor.importBatch(batch);

    assertThat(result.upserted()).isZero();
    assertThat(result.rejected()).isZero();
    verify(importService, never()).upsert(any(), any(), any(), any(), any(), any(), any(), any());
    var captor = ArgumentCaptor.forClass(SyncWatermarkEntity.class);
    verify(watermarks).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getLastSheetNo()).isEqualTo("EXT-00001");
  }

  @Test
  @DisplayName("13.1-BP-003 P0 unknown status: row skipped, no upsert")
  void unknownStatusSkipsRow() {
    var m = machine();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(categories.findByCode("01")).thenAnswer(invocation -> {
      var c = org.mockito.Mockito.mock(
          com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity.class);
      lenient().when(c.getId()).thenReturn(CATEGORY_ID);
      return Optional.of(c);
    });

    var batch = List.of(row("EXT-00001", "MC-001", "01", "NOT_A_STATUS"));
    var result = processor.importBatch(batch);

    assertThat(result.upserted()).isZero();
    assertThat(result.rejected()).isZero();
    verify(importService, never()).upsert(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("13.1-BP-004 P0 mapped rows upserted, watermark advanced to last sheet_no")
  void mappedRowsUpsertAndAdvanceWatermark() {
    var m = machine();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(categories.findByCode("01")).thenAnswer(invocation -> {
      var c = org.mockito.Mockito.mock(
          com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity.class);
      lenient().when(c.getId()).thenReturn(CATEGORY_ID);
      return Optional.of(c);
    });
    lenient().when(importService.upsert(any(), eq(MACHINE_ID), eq(CATEGORY_ID), any(),
        any(), any(), any(), any())).thenReturn(UpsertResult.updated());

    var batch = List.of(
        row("EXT-00001", "MC-001", "01", "OPEN"),
        row("EXT-00002", "MC-001", "01", "DONE"));
    var result = processor.importBatch(batch);

    assertThat(result.upserted()).isEqualTo(2);
    assertThat(result.rejected()).isZero();
    verify(importService).upsert("EXT-00001", MACHINE_ID, CATEGORY_ID, WorkOrderStatus.OPEN,
        "desc EXT-00001", null, NOW, NOW);
    verify(importService).upsert("EXT-00002", MACHINE_ID, CATEGORY_ID, WorkOrderStatus.PENDING_REVIEW,
        "desc EXT-00002", null, NOW, NOW);
    var captor = ArgumentCaptor.forClass(SyncWatermarkEntity.class);
    verify(watermarks).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getLastSheetNo()).isEqualTo("EXT-00002");
  }

  @Test
  @DisplayName("13.1-BP-005 P0 batch failure propagates so the transaction rolls back (watermark not advanced)")
  void failingRowPropagatesForRollback() {
    var m = machine();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(categories.findByCode("01")).thenAnswer(invocation -> {
      var c = org.mockito.Mockito.mock(
          com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity.class);
      lenient().when(c.getId()).thenReturn(CATEGORY_ID);
      return Optional.of(c);
    });
    when(importService.upsert("EXT-00001", MACHINE_ID, CATEGORY_ID, WorkOrderStatus.OPEN,
        "desc EXT-00001", null, NOW, NOW)).thenReturn(UpsertResult.updated());
    when(importService.upsert("EXT-00002", MACHINE_ID, CATEGORY_ID, WorkOrderStatus.PENDING_REVIEW,
        "desc EXT-00002", null, NOW, NOW)).thenThrow(new RuntimeException("constraint violation"));

    var batch = List.of(
        row("EXT-00001", "MC-001", "01", "OPEN"),
        row("EXT-00002", "MC-001", "01", "DONE"));

    try {
      processor.importBatch(batch);
    } catch (RuntimeException expected) {
      // Propagated as expected — the @Transactional proxy rolls back.
    }

    // The watermark write happens after the loop; a rollback means it is never committed.
    verify(watermarks, never()).saveAndFlush(any(SyncWatermarkEntity.class));
  }

  // =========================================================================
  // Story 13-2: Parent-close gate
  // =========================================================================

  @Test
  @DisplayName("13.2-BP-006 P0 parent-close gate: child of CLOSED parent is quarantined, not upserted")
  void parentClosedRejectsChild() {
    // Parent exists and is CLOSED.
    var parent = new WorkOrderEntity("PARENT-001", "EXTERNAL", null, WorkOrderStatus.CLOSED,
        CATEGORY_ID, MACHINE_ID, "parent", 0L, null, null, null, NOW, NOW);
    when(workOrders.findById("PARENT-001")).thenReturn(Optional.of(parent));

    var batch = List.of(childRow("CHILD-001", "PARENT-001"));
    var result = processor.importBatch(batch);

    assertThat(result.upserted()).isZero();
    assertThat(result.rejected()).isEqualTo(1);
    assertThat(result.entries()).hasSize(1);
    assertThat(result.entries().get(0).sheetNo()).isEqualTo("CHILD-001");
    assertThat(result.entries().get(0).reason()).isEqualTo(UpsertResult.PARENT_CLOSED);
    verify(importService, never()).upsert(any(), any(), any(), any(), any(), any(), any(), any());
    // Quarantine row was persisted.
    verify(quarantine).saveAndFlush(any(SyncQuarantineEntity.class));
  }

  @Test
  @DisplayName("13.2-BP-007 P0 parent-close gate: child of non-existent parent is allowed (no parent to check)")
  void parentNotExistsAllowsChild() {
    when(workOrders.findById("PARENT-001")).thenReturn(Optional.empty());
    var m = machine();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(categories.findByCode("01")).thenAnswer(invocation -> {
      var c = org.mockito.Mockito.mock(
          com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity.class);
      lenient().when(c.getId()).thenReturn(CATEGORY_ID);
      return Optional.of(c);
    });
    when(importService.upsert(any(), eq(MACHINE_ID), eq(CATEGORY_ID), any(),
        any(), any(), any(), any())).thenReturn(UpsertResult.updated());

    var batch = List.of(childRow("CHILD-001", "PARENT-001"));
    var result = processor.importBatch(batch);

    assertThat(result.upserted()).isEqualTo(1);
    assertThat(result.rejected()).isZero();
    verify(quarantine, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("13.2-BP-008 P0 parent-close gate: child of non-CLOSED parent is allowed")
  void parentNotClosedAllowsChild() {
    var parent = new WorkOrderEntity("PARENT-001", "EXTERNAL", null, WorkOrderStatus.IN_PROGRESS,
        CATEGORY_ID, MACHINE_ID, "parent", 0L, null, null, null, NOW, NOW);
    when(workOrders.findById("PARENT-001")).thenReturn(Optional.of(parent));
    var m = machine();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(categories.findByCode("01")).thenAnswer(invocation -> {
      var c = org.mockito.Mockito.mock(
          com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity.class);
      lenient().when(c.getId()).thenReturn(CATEGORY_ID);
      return Optional.of(c);
    });
    when(importService.upsert(any(), eq(MACHINE_ID), eq(CATEGORY_ID), any(),
        any(), any(), any(), any())).thenReturn(UpsertResult.updated());

    var batch = List.of(childRow("CHILD-001", "PARENT-001"));
    var result = processor.importBatch(batch);

    assertThat(result.upserted()).isEqualTo(1);
    assertThat(result.rejected()).isZero();
    verify(quarantine, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("13.2-BP-009 P0 parent-close gate: row without parentSheetNo is not checked")
  void noParentAllowed() {
    var m = machine();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(categories.findByCode("01")).thenAnswer(invocation -> {
      var c = org.mockito.Mockito.mock(
          com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity.class);
      lenient().when(c.getId()).thenReturn(CATEGORY_ID);
      return Optional.of(c);
    });
    when(importService.upsert(any(), eq(MACHINE_ID), eq(CATEGORY_ID), any(),
        any(), any(), any(), any())).thenReturn(UpsertResult.updated());

    var batch = List.of(row("EXT-00001", "MC-001", "01", "OPEN"));
    var result = processor.importBatch(batch);

    assertThat(result.upserted()).isEqualTo(1);
    verify(workOrders, never()).findById(any());
  }

  // =========================================================================
  // Story 13-2: Quarantine writes for import service rejections
  // =========================================================================

  @Test
  @DisplayName("13.2-BP-010 P0 quarantine write: import service rejection triggers quarantine row")
  void quarantineOnImportRejection() {
    var m = machine();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(categories.findByCode("01")).thenAnswer(invocation -> {
      var c = org.mockito.Mockito.mock(
          com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity.class);
      lenient().when(c.getId()).thenReturn(CATEGORY_ID);
      return Optional.of(c);
    });
    // Import service rejects the row.
    when(importService.upsert(any(), eq(MACHINE_ID), eq(CATEGORY_ID), any(),
        any(), any(), any(), any()))
        .thenReturn(UpsertResult.rejected(UpsertResult.TERMINAL_STATE_PROTECTED));

    var batch = List.of(row("EXT-00001", "MC-001", "01", "OPEN"));
    var result = processor.importBatch(batch);

    assertThat(result.upserted()).isZero();
    assertThat(result.rejected()).isEqualTo(1);
    assertThat(result.entries()).hasSize(1);
    assertThat(result.entries().get(0).reason()).isEqualTo(UpsertResult.TERMINAL_STATE_PROTECTED);

    // Quarantine row was persisted with row payload.
    var captor = ArgumentCaptor.forClass(SyncQuarantineEntity.class);
    verify(quarantine).saveAndFlush(captor.capture());
    var q = captor.getValue();
    assertThat(q.getSheetNo()).isEqualTo("EXT-00001");
    assertThat(q.getReason()).isEqualTo(UpsertResult.TERMINAL_STATE_PROTECTED);
    assertThat(q.getRawPayload()).isNotNull();
    assertThat(q.getRawPayload()).containsKey("sheetNo");
    assertThat(q.getRawPayload().get("sheetNo")).isEqualTo("EXT-00001");
  }

  @Test
  @DisplayName("13.2-BP-014 P0 batch with rejections: 2 upserted, 3 quarantined, watermark advances past last row")
  void batchWithRejectionsCountsBoth() {
    var m = machine();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(categories.findByCode("01")).thenAnswer(invocation -> {
      var c = org.mockito.Mockito.mock(
          com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity.class);
      lenient().when(c.getId()).thenReturn(CATEGORY_ID);
      return Optional.of(c);
    });
    when(importService.upsert(eq("EXT-00001"), eq(MACHINE_ID), eq(CATEGORY_ID), any(),
        any(), any(), any(), any())).thenReturn(UpsertResult.updated());
    when(importService.upsert(eq("EXT-00002"), eq(MACHINE_ID), eq(CATEGORY_ID), any(),
        any(), any(), any(), any())).thenReturn(UpsertResult.updated());
    // The three rejected rows (one terminal-state rejection, two parent-closed).
    when(importService.upsert(eq("EXT-00003"), eq(MACHINE_ID), eq(CATEGORY_ID), any(),
        any(), any(), any(), any()))
        .thenReturn(UpsertResult.rejected(UpsertResult.TERMINAL_STATE_PROTECTED));
    var closedParent = new WorkOrderEntity("PARENT-CLOSED", "EXTERNAL", null,
        WorkOrderStatus.CLOSED, CATEGORY_ID, MACHINE_ID, "parent", 0L, null, null, null, NOW, NOW);
    when(workOrders.findById("PARENT-CLOSED")).thenReturn(Optional.of(closedParent));

    var batch = List.of(
        row("EXT-00001", "MC-001", "01", "OPEN"),
        row("EXT-00002", "MC-001", "01", "DONE"),
        row("EXT-00003", "MC-001", "01", "OPEN"),
        childRow("EXT-00004", "PARENT-CLOSED"),
        childRow("EXT-00005", "PARENT-CLOSED"));
    var result = processor.importBatch(batch);

    assertThat(result.upserted()).isEqualTo(2);
    assertThat(result.rejected()).isEqualTo(3);
    assertThat(result.entries()).hasSize(3);
    // Watermark advances past the last row (rejected rows are terminal for this cycle).
    var captor = ArgumentCaptor.forClass(SyncWatermarkEntity.class);
    verify(watermarks).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getLastSheetNo()).isEqualTo("EXT-00005");
    // 3 quarantine rows persisted (1 import rejection + 2 parent-closed).
    verify(quarantine, org.mockito.Mockito.times(3))
        .saveAndFlush(any(SyncQuarantineEntity.class));
  }

  // =========================================================================
  // Story 13-2: Fallback category
  // =========================================================================

  @Test
  @DisplayName("13.2-BP-011 P0 fallback category: unmapped code uses fallback when configured")
  void fallbackCategoryUsed() {
    var m = machine();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    // External category '99' is unmapped, but fallback '01' exists.
    when(categories.findByCode("99")).thenReturn(Optional.empty());
    when(properties.fallbackCategoryCode()).thenReturn("01");
    when(categories.findByCode("01")).thenAnswer(invocation -> {
      var c = org.mockito.Mockito.mock(
          com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity.class);
      lenient().when(c.getId()).thenReturn(FALLBACK_CATEGORY_ID);
      return Optional.of(c);
    });
    when(importService.upsert(any(), eq(MACHINE_ID), eq(FALLBACK_CATEGORY_ID), any(),
        any(), any(), any(), any())).thenReturn(UpsertResult.updated());

    var batch = List.of(row("EXT-00001", "MC-001", "99", "OPEN"));
    var result = processor.importBatch(batch);

    assertThat(result.upserted()).isEqualTo(1);
    assertThat(result.rejected()).isZero();
    // The upsert was called with the fallback category ID.
    verify(importService).upsert(eq("EXT-00001"), eq(MACHINE_ID), eq(FALLBACK_CATEGORY_ID),
        any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("13.2-BP-012 P0 fallback category: no fallback configured → skip as before")
  void fallbackCategoryNotConfiguredSkips() {
    var m = machine();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(categories.findByCode("99")).thenReturn(Optional.empty());
    // No fallback configured.
    when(properties.fallbackCategoryCode()).thenReturn("");

    var batch = List.of(row("EXT-00001", "MC-001", "99", "OPEN"));
    var result = processor.importBatch(batch);

    assertThat(result.upserted()).isZero();
    assertThat(result.rejected()).isZero();
    verify(importService, never()).upsert(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("13.2-BP-013 P0 fallback category: null external code with fallback configured uses fallback")
  void fallbackCategoryForNullCode() {
    var m = machine();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(properties.fallbackCategoryCode()).thenReturn("01");
    when(categories.findByCode("01")).thenAnswer(invocation -> {
      var c = org.mockito.Mockito.mock(
          com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity.class);
      lenient().when(c.getId()).thenReturn(FALLBACK_CATEGORY_ID);
      return Optional.of(c);
    });
    when(importService.upsert(any(), eq(MACHINE_ID), eq(FALLBACK_CATEGORY_ID), any(),
        any(), any(), any(), any())).thenReturn(UpsertResult.updated());

    var batch = List.of(new SyncSourceRow("EXT-00001", "MC-001", null, "OPEN", "desc", NOW, NOW, null));
    var result = processor.importBatch(batch);

    assertThat(result.upserted()).isEqualTo(1);
  }

  @Test
  @DisplayName("13.1-BP-006 P0 mapStatus maps valid statuses, normalizes case, rejects unknown/null")
  void mapStatusDirect() {
    assertThat(SyncBatchProcessor.mapStatus("OPEN")).isEqualTo(WorkOrderStatus.OPEN);
    assertThat(SyncBatchProcessor.mapStatus("DONE")).isEqualTo(WorkOrderStatus.PENDING_REVIEW);
    assertThat(SyncBatchProcessor.mapStatus("CLOSED")).isEqualTo(WorkOrderStatus.CLOSED);
    assertThat(SyncBatchProcessor.mapStatus(" in_progress ")).isEqualTo(WorkOrderStatus.IN_PROGRESS);
    assertThat(SyncBatchProcessor.mapStatus("NOT_A_STATUS")).isNull();
    assertThat(SyncBatchProcessor.mapStatus(null)).isNull();
  }
}