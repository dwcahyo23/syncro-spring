package com.syncro.sync.domain;

/**
 * Result of a single workorder upsert (story 13-2, FR-152/NFR-P2-9). Drives the
 * batch processor's quarantine writes and the per-run counts: CREATED/UPDATED rows
 * count as upserted, REJECTED rows are written to {@code sync_quarantine} with
 * {@code reason} (TERMINAL_STATE_PROTECTED / ON_PROCUREMENT_PROTECTED / PARENT_CLOSED).
 *
 * @param action          CREATED, UPDATED or REJECTED
 * @param reason          rejection reason code, or null on success
 * @param quarantineReason alias of {@code reason} kept for the quarantine payload
 *                         contract ({@link SyncBatchProcessor} persists it verbatim)
 */
public record UpsertResult(Action action, String reason, String quarantineReason) {

  public enum Action {
    CREATED,
    UPDATED,
    REJECTED
  }

  public static final String TERMINAL_STATE_PROTECTED = "TERMINAL_STATE_PROTECTED";
  public static final String ON_PROCUREMENT_PROTECTED = "ON_PROCUREMENT_PROTECTED";
  public static final String PARENT_CLOSED = "PARENT_CLOSED";

  public static UpsertResult created() {
    return new UpsertResult(Action.CREATED, null, null);
  }

  public static UpsertResult updated() {
    return new UpsertResult(Action.UPDATED, null, null);
  }

  public static UpsertResult rejected(String reason) {
    return new UpsertResult(Action.REJECTED, reason, reason);
  }

  public boolean isRejected() {
    return action == Action.REJECTED;
  }
}
