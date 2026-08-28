package com.syncro.sync.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * External row from {@code sch_ot.mow_mtn_appm} (the reference system's workorder table).
 * Mapped by {@link com.syncro.sync.infrastructure.SyncSourceReader} via JdbcTemplate.
 */
public record SyncSourceRow(
    String sheetNo,
    String machineCode,
    String categoryCode,
    String status,
    String description,
    Instant createdAt,
    Instant updatedAt,
    String parentSheetNo) {

  /**
   * Serializes the row as a JSON-friendly map for the {@code sync_quarantine.raw_payload}
   * column (story 13-2). Timestamps stay ISO-8601 UTC strings so the payload is readable
   * by an operator diagnosing a rejection.
   */
  public Map<String, Object> toPayload() {
    var payload = new HashMap<String, Object>();
    payload.put("sheetNo", sheetNo);
    payload.put("machineCode", machineCode);
    payload.put("categoryCode", categoryCode);
    payload.put("status", status);
    payload.put("description", description);
    payload.put("createdAt", createdAt != null ? createdAt.toString() : null);
    payload.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
    payload.put("parentSheetNo", parentSheetNo);
    return payload;
  }
}
