package com.syncro.sync.domain;

import java.time.Instant;

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
}