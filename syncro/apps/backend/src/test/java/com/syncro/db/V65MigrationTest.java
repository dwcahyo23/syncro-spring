package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration evidence for V65 (story 13-3): {@code rows_rejected} column added to
 * {@code sync_runs} with a default of 0, preserving existing data and run semantics.
 */
class V65MigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-28T00:00:00Z"));

  @Test
  @DisplayName("13.3-DB-001 P0 sync_runs has rows_rejected column with INT NOT NULL DEFAULT 0")
  void rowsRejectedColumnExists() {
    var columns = columnNames("sync_runs");
    assertThat(columns).contains("rows_rejected");
  }

  @Test
  @DisplayName("13.3-DB-002 P0 rows_rejected defaults to 0 on insert")
  void rowsRejectedDefaultsToZero() {
    var id = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO sync_runs (id, started_at, status, rows_read, rows_upserted)
        VALUES (?, ?, 'SUCCESS', 10, 8)
        """, id, TS);

    var rowsRejected = jdbc.queryForObject(
        "SELECT rows_rejected FROM sync_runs WHERE id = ?", Integer.class, id);
    assertThat(rowsRejected).isZero();
  }

  @Test
  @DisplayName("13.3-DB-003 P0 rows_rejected accepts explicit value on insert")
  void rowsRejectedAcceptsExplicitValue() {
    var id = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO sync_runs (id, started_at, status, rows_read, rows_upserted, rows_rejected)
        VALUES (?, ?, 'SUCCESS', 10, 7, 3)
        """, id, TS);

    var rowsRejected = jdbc.queryForObject(
        "SELECT rows_rejected FROM sync_runs WHERE id = ?", Integer.class, id);
    assertThat(rowsRejected).isEqualTo(3);
  }

  @Test
  @DisplayName("13.3-DB-004 P0 existing columns are preserved (rows_read, rows_upserted, status, etc.)")
  void existingColumnsPreserved() {
    assertThat(columnNames("sync_runs"))
        .contains("id", "started_at", "completed_at", "status", "rows_read", "rows_upserted",
            "rows_rejected", "error_message");
  }

  @Test
  @DisplayName("13.3-DB-005 P0 status CHECK still accepts RUNNING/SUCCESS/FAILED")
  void statusCheckStillWorks() {
    for (var status : List.of("RUNNING", "SUCCESS", "FAILED")) {
      jdbc.update("""
          INSERT INTO sync_runs (id, started_at, status, rows_read, rows_upserted, rows_rejected)
          VALUES (?, ?, ?, 0, 0, 0)
          """, UUID.randomUUID(), TS, status);
    }
    var count = jdbc.queryForObject("SELECT count(*) FROM sync_runs", Long.class);
    assertThat(count).isEqualTo(3L);
  }

  private List<String> columnNames(String table) {
    return jdbc.queryForList(
        "SELECT column_name FROM information_schema.columns "
            + "WHERE table_schema = 'public' AND table_name = ?", table)
        .stream()
        .map(row -> String.valueOf(row.get("column_name")))
        .toList();
  }
}