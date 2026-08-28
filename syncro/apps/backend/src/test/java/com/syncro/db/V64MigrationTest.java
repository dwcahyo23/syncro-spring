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
 * Migration evidence for V64 (story 13-2): sync_field_mappings (PK + domain CHECK +
 * seeded MASTER/OPERATIONAL defaults), sync_quarantine (UUID PK, reason CHECK-agnostic,
 * JSONB payload, sheet_no index), and the audit_log entity_type extension with
 * SYNC_QUARANTINE (drop/re-add pattern).
 */
class V64MigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-28T00:00:00Z"));

  @Test
  @DisplayName("13.2-DB-001 P0 sync_field_mappings table exists with expected columns")
  void fieldMappingsTableExists() {
    assertThat(columnNames("sync_field_mappings"))
        .contains("field_name", "domain");
  }

  @Test
  @DisplayName("13.2-DB-002 P0 sync_field_mappings domain CHECK accepts MASTER/OPERATIONAL and rejects unknown")
  void fieldMappingsDomainCheck() {
    for (var domain : List.of("MASTER", "OPERATIONAL")) {
      jdbc.update("""
          INSERT INTO sync_field_mappings (field_name, domain)
          VALUES (?, ?)
          """, "test_field_" + domain, domain);
    }
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO sync_field_mappings (field_name, domain)
        VALUES ('bad_field', 'BOGUS')
        """))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("13.2-DB-003 P0 sync_field_mappings is seeded with the AD-8 default set")
  void fieldMappingsSeeded() {
    // MASTER seeds.
    for (var field : List.of("status", "machine_id", "category_id", "parent_id", "description",
        "sync_version")) {
      assertThat(domainOf("sync_field_mappings", field)).isEqualTo("MASTER");
    }
    // OPERATIONAL seeds.
    for (var field : List.of("report_chronological", "report_analyze", "report_corrective",
        "report_preventive", "cp_cp_lower", "cp_cp_upper", "cpk", "cpk_pdf_object_key",
        "fmea_failure_type", "stop_time_reason", "stop_time_detail", "mttr_minutes",
        "response_time_minutes", "done_reason")) {
      assertThat(domainOf("sync_field_mappings", field)).isEqualTo("OPERATIONAL");
    }
  }

  @Test
  @DisplayName("13.2-DB-004 P0 sync_field_mappings field_name is the PK (duplicate rejected)")
  void fieldMappingsPk() {
    jdbc.update("""
        INSERT INTO sync_field_mappings (field_name, domain)
        VALUES ('dup_field', 'MASTER')
        """);
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO sync_field_mappings (field_name, domain)
        VALUES ('dup_field', 'OPERATIONAL')
        """))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("13.2-DB-005 P0 sync_quarantine table exists with expected columns")
  void quarantineTableExists() {
    assertThat(columnNames("sync_quarantine"))
        .contains("id", "sheet_no", "reason", "raw_payload", "trace_id", "created_at");
  }

  @Test
  @DisplayName("13.2-DB-006 P0 sync_quarantine accepts a row with JSONB payload, reason, trace_id")
  void quarantineInsert() {
    jdbc.update("""
        INSERT INTO sync_quarantine (id, sheet_no, reason, raw_payload, trace_id)
        VALUES (?, ?, ?, ?::jsonb, ?)
        """, UUID.randomUUID(), "EXT-00001", "TERMINAL_STATE_PROTECTED",
        "{\"sheetNo\":\"EXT-00001\",\"status\":\"OPEN\"}", UUID.randomUUID().toString());

    var count = jdbc.queryForObject(
        "SELECT count(*) FROM sync_quarantine WHERE sheet_no = 'EXT-00001'", Long.class);
    assertThat(count).isEqualTo(1L);
  }

  @Test
  @DisplayName("13.2-DB-007 P0 sync_quarantine requires reason and defaults created_at")
  void quarantineConstraints() {
    // Insert a valid row first to verify created_at defaults.
    var id = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO sync_quarantine (id, sheet_no, reason)
        VALUES (?, ?, 'ON_PROCUREMENT_PROTECTED')
        """, id, "EXT-00002");
    var createdAt = jdbc.queryForObject(
        "SELECT created_at FROM sync_quarantine WHERE id = ?", Timestamp.class, id);
    assertThat(createdAt).isNotNull();

    // Missing reason is rejected.
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO sync_quarantine (id, sheet_no)
        VALUES (?, 'EXT-00003')
        """, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("13.2-DB-008 P0 sync_quarantine has a sheet_no index")
  void quarantineIndex() {
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'sync_quarantine'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_sync_quarantine_sheet_no");
  }

  @Test
  @DisplayName("13.2-DB-009 P0 audit_log entity_type accepts SYNC_QUARANTINE")
  void auditEntityTypeAcceptsSyncQuarantine() {
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "SYSTEM", "CREATE", "SYNC_QUARANTINE",
        UUID.randomUUID(), "quarantine row", TS);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'SYNC_QUARANTINE'", Long.class))
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("13.2-DB-010 P0 audit_log CHECK preserves all prior entity types through the V64 re-add")
  void auditEntityTypePreservesExistingTypes() {
    for (var type : List.of("WORK_ORDER", "SPAREPART_REQUEST", "SPAREPART_STOCK", "SYNC_RUN",
        "SYNC_QUARANTINE")) {
      jdbc.update("""
          INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
          VALUES (?,?,?,?,?,?,?,?)
          """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", type,
          UUID.randomUUID(), "existing-" + type, TS);
    }
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type IN ('WORK_ORDER','SPAREPART_REQUEST','SPAREPART_STOCK','SYNC_RUN','SYNC_QUARANTINE')",
        Long.class)).isEqualTo(5L);
  }

  private String domainOf(String table, String fieldName) {
    return jdbc.queryForObject(
        "SELECT domain FROM " + table + " WHERE field_name = ?", String.class, fieldName);
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
