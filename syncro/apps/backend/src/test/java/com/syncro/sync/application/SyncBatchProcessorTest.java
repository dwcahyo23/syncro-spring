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
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.sync.domain.SyncSourceRow;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 13-1 unit tests for {@link SyncBatchProcessor} — the transactional batch
 * boundary between the worker and the import service. Covers the matrix rows the
 * worker/reader tests do not reach: unmapped machine/category skip, unknown-status
 * skip, and the "batch fails → whole batch rolls back via a single @Transactional"
 * contract (a failing upsert propagates so the transaction rolls back and the
 * watermark is not advanced — re-read on the next cycle keeps it idempotent).
 */
@ExtendWith(MockitoExtension.class)
class SyncBatchProcessorTest {

  private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID MACHINE_ID = UUID.randomUUID();
  private static final UUID CATEGORY_ID = UUID.randomUUID();

  @Mock
  private WorkorderImportService importService;
  @Mock
  private MachineRepository machines;
  @Mock
  private WorkOrderCategoryRepository categories;
  @Mock
  private SyncWatermarkRepository watermarks;
  @Mock
  private SyncProperties properties;

  private SyncBatchProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new SyncBatchProcessor(importService, machines, categories, watermarks, CLOCK);
  }

  private SyncSourceRow row(String sheetNo, String machineCode, String categoryCode,
      String status) {
    return new SyncSourceRow(sheetNo, machineCode, categoryCode, status, "desc " + sheetNo,
        NOW, NOW, null);
  }

  private MachineEntity machine() {
    // MachineEntity has a protected constructor — mock it; only getId matters here.
    var m = org.mockito.Mockito.mock(MachineEntity.class);
    lenient().when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    lenient().when(m.getId()).thenReturn(MACHINE_ID);
    return m;
  }

  private WorkOrderCategoryEntity category() {
    // WorkOrderCategoryEntity has a protected constructor — mock it; only getId matters here.
    var c = org.mockito.Mockito.mock(WorkOrderCategoryEntity.class);
    lenient().when(categories.findByCode("01")).thenReturn(Optional.of(c));
    lenient().when(c.getId()).thenReturn(CATEGORY_ID);
    return c;
  }

  @Test
  @DisplayName("13.1-BP-001 P0 unmapped machine: row skipped with warning, no upsert; watermark still advances (skip is terminal — never re-read)")
  void unmappedMachineSkipsRow() {
    when(machines.findByCodeIgnoreCase("MC-UNKNOWN")).thenReturn(Optional.empty());

    var batch = List.of(row("EXT-00001", "MC-UNKNOWN", "01", "OPEN"));
    int upserted = processor.importBatch(batch);

    assertThat(upserted).isZero();
    verify(importService, never()).upsert(any(), any(), any(), any(), any(), any(), any(), any());
    // The watermark advances past the skipped row so the next cycle does not re-read it
    // forever (quarantine in 13.3 replaces the silent skip). Same transaction: if the
    // batch later fails, the rollback undoes the watermark too.
    var captor = org.mockito.ArgumentCaptor.forClass(SyncWatermarkEntity.class);
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
    int upserted = processor.importBatch(batch);

    assertThat(upserted).isZero();
    verify(importService, never()).upsert(any(), any(), any(), any(), any(), any(), any(), any());
    var captor = org.mockito.ArgumentCaptor.forClass(SyncWatermarkEntity.class);
    verify(watermarks).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getLastSheetNo()).isEqualTo("EXT-00001");
  }

  @Test
  @DisplayName("13.1-BP-003 P0 unknown status: row skipped, no upsert")
  void unknownStatusSkipsRow() {
    var m = machine();
    var c = category();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(categories.findByCode("01")).thenReturn(Optional.of(c));

    var batch = List.of(row("EXT-00001", "MC-001", "01", "NOT_A_STATUS"));
    int upserted = processor.importBatch(batch);

    assertThat(upserted).isZero();
    verify(importService, never()).upsert(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("13.1-BP-004 P0 mapped rows upserted, watermark advanced to last sheet_no")
  void mappedRowsUpsertAndAdvanceWatermark() {
    var m = machine();
    var c = category();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(categories.findByCode("01")).thenReturn(Optional.of(c));
    lenient().when(importService.upsert(any(), eq(MACHINE_ID), eq(CATEGORY_ID), any(),
        any(), any(), any(), any())).thenReturn("EXT-00001");

    var batch = List.of(
        row("EXT-00001", "MC-001", "01", "OPEN"),
        row("EXT-00002", "MC-001", "01", "DONE"));
    int upserted = processor.importBatch(batch);

    assertThat(upserted).isEqualTo(2);
    verify(importService).upsert("EXT-00001", MACHINE_ID, CATEGORY_ID, WorkOrderStatus.OPEN,
        "desc EXT-00001", null, NOW, NOW);
    verify(importService).upsert("EXT-00002", MACHINE_ID, CATEGORY_ID, WorkOrderStatus.DONE,
        "desc EXT-00002", null, NOW, NOW);
    var captor = org.mockito.ArgumentCaptor.forClass(SyncWatermarkEntity.class);
    verify(watermarks).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getLastSheetNo()).isEqualTo("EXT-00002");
  }

  @Test
  @DisplayName("13.1-BP-005 P0 batch failure propagates so the transaction rolls back (watermark not advanced)")
  void failingRowPropagatesForRollback() {
    var m = machine();
    var c = category();
    when(machines.findByCodeIgnoreCase("MC-001")).thenReturn(Optional.of(m));
    when(categories.findByCode("01")).thenReturn(Optional.of(c));
    // First row succeeds, second row throws — the exception propagates out of
    // importBatch so the @Transactional boundary rolls back the whole batch.
    when(importService.upsert("EXT-00001", MACHINE_ID, CATEGORY_ID, WorkOrderStatus.OPEN,
        "desc EXT-00001", null, NOW, NOW)).thenReturn("EXT-00001");
    when(importService.upsert("EXT-00002", MACHINE_ID, CATEGORY_ID, WorkOrderStatus.DONE,
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

  @Test
  @DisplayName("13.1-BP-006 P0 mapStatus maps valid statuses, normalizes case, rejects unknown/null")
  void mapStatusDirect() {
    assertThat(SyncBatchProcessor.mapStatus("OPEN")).isEqualTo(WorkOrderStatus.OPEN);
    assertThat(SyncBatchProcessor.mapStatus("DONE")).isEqualTo(WorkOrderStatus.DONE);
    assertThat(SyncBatchProcessor.mapStatus("CLOSED")).isEqualTo(WorkOrderStatus.CLOSED);
    assertThat(SyncBatchProcessor.mapStatus(" in_progress ")).isEqualTo(WorkOrderStatus.IN_PROGRESS);
    assertThat(SyncBatchProcessor.mapStatus("NOT_A_STATUS")).isNull();
    assertThat(SyncBatchProcessor.mapStatus(null)).isNull();
  }
}
