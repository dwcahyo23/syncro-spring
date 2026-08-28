package com.syncro.sync.application;

import java.time.Instant;

/**
 * Sync observability view (story 13-3, FR-153) — read model assembled by
 * {@link SyncStatusService} and returned by {@code GET /api/v1/sync/status}.
 *
 * @param lastRunAt         start of the latest sync run, or null when never run
 * @param status            run status: RUNNING/SUCCESS/FAILED, or NEVER_RUN when no runs exist
 * @param rowsRead          rows read by the latest run
 * @param rowsUpserted      rows upserted by the latest run
 * @param rowsRejected      rows rejected (quarantined) by the latest run
 * @param errorMessage      error detail of the latest run, or null on success
 * @param quarantinedCount  total quarantine rows (append-only evidence)
 * @param lastQuarantinedAt creation time of the most recent quarantine row, or null
 */
public record SyncStatusView(
    Instant lastRunAt,
    String status,
    int rowsRead,
    int rowsUpserted,
    int rowsRejected,
    String errorMessage,
    long quarantinedCount,
    Instant lastQuarantinedAt) {

  /** The never-run view: no sync_runs rows exist yet (SYNC_STATUS_NEVER_RUN). */
  public static SyncStatusView neverRun() {
    return new SyncStatusView(null, "NEVER_RUN", 0, 0, 0, null, 0, null);
  }
}
