package com.syncro.sync.application;

import com.syncro.config.SyncProperties;
import com.syncro.maintenance.application.WorkorderImportService;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.sync.domain.SyncSourceRow;
import com.syncro.sync.infrastructure.SyncWatermarkEntity;
import com.syncro.sync.infrastructure.SyncWatermarkRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional batch processor (AD-7/FR-150, story 13-1). One external batch is
 * processed inside a single Syncro DB transaction: every row is upserted via
 * {@link WorkorderImportService#upsert} (joining this transaction with REQUIRED) and
 * the watermark is advanced in the same transaction. A single failing row rolls back
 * the whole batch — the watermark stays at the previous position and the next poll
 * cycle re-reads the batch from there (idempotent, no duplicates).
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
  private final SyncWatermarkRepository watermarks;
  private final Clock clock;

  public SyncBatchProcessor(WorkorderImportService importService, MachineRepository machines,
      WorkOrderCategoryRepository categories, SyncWatermarkRepository watermarks, Clock clock) {
    this.importService = importService;
    this.machines = machines;
    this.categories = categories;
    this.watermarks = watermarks;
    this.clock = clock;
  }

  /**
   * Upserts the batch and advances the watermark in one transaction.
   *
   * @return number of rows upserted (unmapped/unknown rows are skipped with a warning)
   */
  @Transactional
  public int importBatch(List<SyncSourceRow> batch) {
    int upserted = 0;
    String lastSheetNo = null;
    for (var row : batch) {
      if (upsertRow(row)) {
        upserted++;
      }
      lastSheetNo = row.sheetNo();
    }
    if (lastSheetNo != null) {
      watermarks.saveAndFlush(new SyncWatermarkEntity(lastSheetNo, Instant.now(clock)));
    }
    return upserted;
  }

  /**
   * Resolves machine + category and upserts one external row. Rows with unmapped
   * machine/category or an unknown status are skipped with a warning (quarantine is
   * story 13.3). Returns true when the row was upserted.
   */
  private boolean upsertRow(SyncSourceRow row) {
    var machine = row.machineCode() == null ? null
        : machines.findByCodeIgnoreCase(row.machineCode()).orElse(null);
    if (machine == null) {
      log.warn("[SyncWorker] skip sheet_no={} — machine_code '{}' not mapped",
          row.sheetNo(), row.machineCode());
      return false;
    }
    var category = row.categoryCode() == null ? null
        : categories.findByCode(row.categoryCode().toUpperCase()).orElse(null);
    if (category == null) {
      log.warn("[SyncWorker] skip sheet_no={} — category_code '{}' not mapped",
          row.sheetNo(), row.categoryCode());
      return false;
    }
    var status = mapStatus(row.status());
    if (status == null) {
      log.warn("[SyncWorker] skip sheet_no={} — unknown external status '{}'",
          row.sheetNo(), row.status());
      return false;
    }
    importService.upsert(row.sheetNo(), machine.getId(), category.getId(), status,
        row.description(), row.parentSheetNo(), row.createdAt(), row.updatedAt());
    return true;
  }

  /** External status strings map directly to the local lifecycle (e.g. OPEN/IN_PROGRESS/DONE/CLOSED). */
  static WorkOrderStatus mapStatus(String externalStatus) {
    if (externalStatus == null) {
      return null;
    }
    try {
      return WorkOrderStatus.valueOf(externalStatus.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}