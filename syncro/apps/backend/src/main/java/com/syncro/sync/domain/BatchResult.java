package com.syncro.sync.domain;

/**
 * Result of a single batch import (story 13-2). {@code upserted} counts CREATED + UPDATED
 * rows, {@code rejected} counts quarantined rows, and {@code entries} carries the full
 * quarantine details for the run record.
 */
public record BatchResult(int upserted, int rejected, java.util.List<QuarantineEntry> entries) {

  public static final BatchResult EMPTY = new BatchResult(0, 0, java.util.List.of());

  public BatchResult {
    if (upserted < 0) throw new IllegalArgumentException("upserted must be >= 0");
    if (rejected < 0) throw new IllegalArgumentException("rejected must be >= 0");
  }

  public record QuarantineEntry(String sheetNo, String reason, String rawPayload, String traceId) {
  }
}