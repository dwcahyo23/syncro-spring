package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration evidence for V63 (story 13-1): sync_watermarks (single-row CHECK guard),
 * sync_runs (status CHECK + counters), and the audit_log entity_type extension with
 * SYNC_RUN (drop/re-add pattern).
 */
class SyncPipelineMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-28T00:00:00Z"));
  private static final UUID FIXED_WATERMARK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  @DisplayName("13.1-DB-001 P0 sync_watermarks table exists with expected columns")
  void watermarkTableExists() {
    assertThat(columnNames("sync_watermarks"))
        .contains("id", "last_sheet_no", "updated_at");
  }

  @Test
  @DisplayName("13.1-DB-002 P0 sync_watermarks CHECK guards the single row id")
  void watermarkSingleRowCheck() {
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO sync_watermarks (id, last_sheet_no, updated_at)
        VALUES (?, 'EXT-00001', ?)
        """, UUID.randomUUID(), TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("13.1-DB-003 P0 sync_watermarks upsert on conflict advances last_sheet_no")
  void watermarkUpsert() {
    jdbc.update("""
        INSERT INTO sync_watermarks (id, last_sheet_no, updated_at)
        VALUES (?, 'EXT-00001', ?)
        ON CONFLICT (id) DO UPDATE SET last_sheet_no = EXCLUDED.last_sheet_no, updated_at = EXCLUDED.updated_at
        """, FIXED_WATERMARK_ID, TS);
    jdbc.update("""
        INSERT INTO sync_watermarks (id, last_sheet_no, updated_at)
        VALUES (?, 'EXT-00002', ?)
        ON CONFLICT (id) DO UPDATE SET last_sheet_no = EXCLUDED.last_sheet_no, updated_at = EXCLUDED.updated_at
        """, FIXED_WATERMARK_ID, TS);

    var count = jdbc.queryForObject("SELECT count(*) FROM sync_watermarks", Long.class);
    assertThat(count).isEqualTo(1L);
    var lastSheetNo = jdbc.queryForObject(
        "SELECT last_sheet_no FROM sync_watermarks WHERE id = ?", String.class, FIXED_WATERMARK_ID);
    assertThat(lastSheetNo).isEqualTo("EXT-00002");
  }

  @Test
  @DisplayName("13.1-DB-004 P0 sync_runs table exists with expected columns")
  void runsTableExists() {
    assertThat(columnNames("sync_runs"))
        .contains("id", "started_at", "completed_at", "status", "rows_read", "rows_upserted",
            "error_message");
  }

  @Test
  @DisplayName("13.1-DB-005 P0 sync_runs status CHECK accepts RUNNING/SUCCESS/FAILED and rejects unknown")
  void runsStatusCheck() {
    for (var status : List.of("RUNNING", "SUCCESS", "FAILED")) {
      jdbc.update("""
          INSERT INTO sync_runs (id, started_at, status, rows_read, rows_upserted)
          VALUES (?, ?, ?, 0, 0)
          """, UUID.randomUUID(), TS, status);
    }
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO sync_runs (id, started_at, status)
        VALUES (?, ?, 'BOGUS')
        """, UUID.randomUUID(), TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("13.1-DB-006 P0 sync_runs defaults rows_read/rows_upserted to 0 and accepts error_message")
  void runsDefaultsAndError() {
    jdbc.update("""
        INSERT INTO sync_runs (id, started_at, status, error_message)
        VALUES (?, ?, 'FAILED', 'connection refused')
        """, UUID.randomUUID(), TS);
    var row = jdbc.queryForMap(
        "SELECT rows_read, rows_upserted, error_message FROM sync_runs WHERE status = 'FAILED'");
    assertThat(((Number) row.get("rows_read")).intValue()).isZero();
    assertThat(((Number) row.get("rows_upserted")).intValue()).isZero();
    assertThat(row.get("error_message")).isEqualTo("connection refused");
  }

  @Test
  @DisplayName("13.1-DB-007 P0 audit_log entity_type accepts SYNC_RUN")
  void auditEntityTypeAcceptsSyncRun() {
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "SYSTEM", "CREATE", "SYNC_RUN",
        UUID.randomUUID(), "sync run", TS);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'SYNC_RUN'", Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("13.1-DB-008 P0 audit_log CHECK preserves all prior entity types through the V63 re-add")
  void auditEntityTypePreservesExistingTypes() {
    for (var type : List.of("WORK_ORDER", "PREVENTIVE_ATTACHMENT", "SPAREPART_REQUEST",
        "SPAREPART_STOCK", "SYNC_RUN")) {
      jdbc.update("""
          INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
          VALUES (?,?,?,?,?,?,?,?)
          """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", type,
          UUID.randomUUID(), "existing-" + type, TS);
    }
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type IN ('WORK_ORDER','PREVENTIVE_ATTACHMENT','SPAREPART_REQUEST','SPAREPART_STOCK','SYNC_RUN')",
        Long.class)).isEqualTo(5L);
  }

  @Test
  @DisplayName("13.1-DB-009 P0 sync_runs has started_at index")
  void runsIndexesExist() {
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'sync_runs'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_sync_runs_started_at");
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