package com.syncro.sync.application;

import com.syncro.authz.application.PolicyDecisionPoint;
import com.syncro.config.SyncProperties;
import com.syncro.maintenance.application.WorkorderImportService;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional batch processor (AD-7/FR-150, story 13-1/13-2). One external batch is
 * processed inside a single Syncro DB transaction: every row is upserted via
 * {@link WorkorderImportService#upsert} (joining this transaction with REQUIRED),
 * rejected rows are persisted to {@code sync_quarantine}, and the watermark is advanced
 * in the same transaction. A single failing row rolls back the whole batch — the
 * watermark stays at the previous position and the next poll cycle re-reads the batch
 * from there (idempotent, no duplicates).
 *
 * <p>Conflict resolution (story 13-2, NFR-P2-9): the parent-close gate runs here, before
 * the upsert, for both create and update — a child of a CLOSED parent is quarantined
 * ({@code PARENT_CLOSED}) and never upserted. Rejections returned by the import service
 * ({@code TERMINAL_STATE_PROTECTED}, {@code ON_PROCUREMENT_PROTECTED}) are also written
 * to quarantine. The {@code raw_payload} is the external row serialized as JSON so an
 * operator can diagnose the rejection. A configurable fallback category
 * ({@link SyncProperties#fallbackCategoryCode}) replaces the hard skip when an external
 * category code is unmapped; without a fallback the row is skipped as before.
 *
 * <p>This is a separate bean (not a private method of the worker) so the
 * {@code @Transactional} boundary is a real Spring proxy, not a self-invocation.
 */
@Service
@ConditionalOnProperty(prefix = "syncro.sync", name = "enabled", havingValue = "true")
public class SyncBatchProcessor {

  private static final Logger log = LoggerFactory.getLogger(SyncBatchProcessor.class);

  private final WorkorderImportService importService;
  private final MachineRepository machines;
  private final WorkOrderCategoryRepository categories;
  private final WorkOrderRepository workOrders;
  private final SyncWatermarkRepository watermarks;
  private final SyncQuarantineRepository quarantine;
  private final SyncProperties properties;
  private final Clock clock;

  public SyncBatchProcessor(WorkorderImportService importService, MachineRepository machines,
      WorkOrderCategoryRepository categories, WorkOrderRepository workOrders,
      SyncWatermarkRepository watermarks, SyncQuarantineRepository quarantine,
      SyncProperties properties, Clock clock) {
    this.importService = importService;
    this.machines = machines;
    this.categories = categories;
    this.workOrders = workOrders;
    this.watermarks = watermarks;
    this.quarantine = quarantine;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Upserts the batch and advances the watermark in one transaction. Rejected rows are
   * quarantined in the same transaction; the watermark always advances past the last row
   * (a rejected row is terminal for this cycle — re-reading it would quarantine it forever).
   *
   * @return counts of upserted/rejected rows plus the quarantine entries written
   */
  @Transactional
  public BatchResult importBatch(List<SyncSourceRow> batch) {
    int upserted = 0;
    int rejected = 0;
    var entries = new ArrayList<BatchResult.QuarantineEntry>();
    String lastSheetNo = null;
    for (var row : batch) {
      var outcome = processRow(row);
      if (outcome.upserted()) {
        upserted++;
      } else if (outcome.rejected()) {
        rejected++;
      }
      if (outcome.quarantineEntry() != null) {
        entries.add(outcome.quarantineEntry());
      }
      lastSheetNo = row.sheetNo();
    }
    if (lastSheetNo != null) {
      watermarks.saveAndFlush(new SyncWatermarkEntity(lastSheetNo, Instant.now(clock)));
    }
    return new BatchResult(upserted, rejected, List.copyOf(entries));
  }

  private RowOutcome processRow(SyncSourceRow row) {
    // Parent-close gate (NFR-P2-9): never create or update a child of a CLOSED parent.
    if (isParentClosed(row)) {
      log.warn("[SyncWorker] reject sheet_no={} — parent '{}' is CLOSED",
          row.sheetNo(), row.parentSheetNo());
      return rejected(row, UpsertResult.PARENT_CLOSED);
    }

    var machine = row.machineCode() == null ? null
        : machines.findByCodeIgnoreCase(row.machineCode()).orElse(null);
    if (machine == null) {
      log.warn("[SyncWorker] skip sheet_no={} — machine_code '{}' not mapped",
          row.sheetNo(), row.machineCode());
      return new RowOutcome(false, false, null);
    }
    var categoryId = resolveCategory(row);
    if (categoryId == null) {
      log.warn("[SyncWorker] skip sheet_no={} — category_code '{}' not mapped (no fallback configured)",
          row.sheetNo(), row.categoryCode());
      return new RowOutcome(false, false, null);
    }
    var status = mapStatus(row.status());
    if (status == null) {
      log.warn("[SyncWorker] skip sheet_no={} — unknown external status '{}'",
          row.sheetNo(), row.status());
      return new RowOutcome(false, false, null);
    }

    var result = importService.upsert(row.sheetNo(), machine.getId(), categoryId, status,
        row.description(), row.parentSheetNo(), row.createdAt(), row.updatedAt());
    if (result.isRejected()) {
      log.warn("[SyncWorker] reject sheet_no={} — reason '{}'",
          row.sheetNo(), result.reason());
      return rejected(row, result.reason());
    }
    return new RowOutcome(true, false, null);
  }

  /** A child is rejected when the parent workorder exists and is CLOSED (NFR-P2-9). */
  private boolean isParentClosed(SyncSourceRow row) {
    if (row.parentSheetNo() == null) {
      return false;
    }
    return workOrders.findById(row.parentSheetNo())
        .map(parent -> parent.getStatus() == WorkOrderStatus.CLOSED)
        .orElse(false);
  }

  /**
   * Resolves the external category code to a local category. When the code is unmapped
   * and a fallback category is configured ({@link SyncProperties#fallbackCategoryCode}),
   * the fallback is used instead of skipping (OQ-4 minimum viable path). Returns null
   * when neither the code nor a fallback resolves.
   */
  private java.util.UUID resolveCategory(SyncSourceRow row) {
    if (row.categoryCode() != null) {
      var category = categories.findByCode(row.categoryCode().toUpperCase()).orElse(null);
      if (category != null) {
        return category.getId();
      }
      var fallback = properties.fallbackCategoryCode();
      if (fallback != null && !fallback.isBlank()) {
        var fallbackCategory = categories.findByCode(fallback.toUpperCase()).orElse(null);
        if (fallbackCategory != null) {
          log.warn("[SyncWorker] sheet_no={} — category '{}' unmapped, using fallback '{}'",
              row.sheetNo(), row.categoryCode(), fallback);
          return fallbackCategory.getId();
        }
      }
    } else if (properties.fallbackCategoryCode() != null && !properties.fallbackCategoryCode().isBlank()) {
      var fallback = properties.fallbackCategoryCode().toUpperCase();
      var fallbackCategory = categories.findByCode(fallback).orElse(null);
      if (fallbackCategory != null) {
        log.warn("[SyncWorker] sheet_no={} — no external category, using fallback '{}'",
            row.sheetNo(), fallback);
        return fallbackCategory.getId();
      }
    }
    return null;
  }

  private RowOutcome rejected(SyncSourceRow row, String reason) {
    var entry = new BatchResult.QuarantineEntry(row.sheetNo(), reason, null, null);
    quarantine.saveAndFlush(new SyncQuarantineEntity(UUID.randomUUID(), row.sheetNo(), reason,
        row.toPayload(), PolicyDecisionPoint.currentTraceId(), Instant.now(clock)));
    return new RowOutcome(false, true, entry);
  }

  /**
   * External status strings map to the local 6-value lifecycle (story 15-1 remap).
   * Legacy external values are translated onto the new enum: ASSIGNED → IN_PROGRESS
   * (assign-starts-execution), ON_PROCUREMENT → PENDING_SPAREPART, DONE →
   * PENDING_REVIEW, DRAFT → OPEN; everything else maps by exact name.
   */
  static WorkOrderStatus mapStatus(String externalStatus) {
    if (externalStatus == null) {
      return null;
    }
    var normalized = externalStatus.trim().toUpperCase();
    try {
      return switch (normalized) {
        case "DRAFT" -> WorkOrderStatus.OPEN;
        case "ASSIGNED" -> WorkOrderStatus.IN_PROGRESS;
        case "ON_PROCUREMENT" -> WorkOrderStatus.PENDING_SPAREPART;
        case "DONE" -> WorkOrderStatus.PENDING_REVIEW;
        default -> WorkOrderStatus.valueOf(normalized);
      };
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Internal carrier for one row's outcome (upserted / rejected / skip with optional quarantine entry). */
  private record RowOutcome(boolean upserted, boolean rejected,
      BatchResult.QuarantineEntry quarantineEntry) {
  }
}
