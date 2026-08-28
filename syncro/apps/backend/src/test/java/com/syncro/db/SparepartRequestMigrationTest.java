package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration evidence for V57 (story 12-1): the sparepart_requests table, its CHECK
 * constraints (request_type, status, quantity), the audit_log entity_type extension,
 * and the workorder FK.
 */
class SparepartRequestMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-27T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("12.1-DB-001 P0 sparepart_requests table exists with expected columns")
  void tableExists() {
    assertThat(columnNames("sparepart_requests"))
        .contains("id", "request_type", "work_order_id", "machine_id", "sparepart_id", "material_code",
            "quantity", "est_price_id", "est_unit_price", "purchase_reference_url", "status", "requested_by",
            "requested_at", "notes", "created_at", "updated_at");
  }

  @Test
  @DisplayName("12.1-DB-002 P0 request_type CHECK rejects unknown values")
  void requestTypeCheck() {
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO sparepart_requests (id, request_type, quantity, requested_by, requested_at, created_at, updated_at)
        VALUES (?, 'BOGUS', 1, ?, ?, ?, ?)
        """, UUID.randomUUID(), UUID.randomUUID(), TS, TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("12.1-DB-003 P0 status CHECK rejects unknown values (V57+V59)")
  void statusCheck() {
    // V57 rejects it; V59 also rejects it but with a different CHECK value set.
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO sparepart_requests (id, request_type, quantity, status, requested_by, requested_at, created_at, updated_at)
        VALUES (?, 'SPAREPART', 1, 'BOGUS', ?, ?, ?, ?)
        """, UUID.randomUUID(), UUID.randomUUID(), TS, TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("12.1-DB-004 P0 quantity CHECK rejects zero/negative")
  void quantityCheck() {
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO sparepart_requests (id, request_type, quantity, requested_by, requested_at, created_at, updated_at)
        VALUES (?, 'SPAREPART', 0, ?, ?, ?, ?)
        """, UUID.randomUUID(), UUID.randomUUID(), TS, TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("12.1-DB-005 P0 audit_log entity_type accepts SPAREPART_REQUEST")
  void auditEntityTypeAcceptsRequest() {
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "SPAREPART_REQUEST",
        UUID.randomUUID(), "SPAREPART MC-0001", TS);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'SPAREPART_REQUEST'", Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("12.1-DB-007 P0 audit_log CHECK preserves pre-existing types through the drop/re-add")
  void auditEntityTypePreservesExistingTypes() {
    // V57 drops and re-adds ck_audit_log_entity_type — a regression that drops a prior type
    // (e.g. PREVENTIVE_ATTACHMENT or WORK_ORDER) must be caught here, not just SPAREPART_REQUEST.
    for (var type : List.of("WORK_ORDER", "PREVENTIVE_ATTACHMENT", "SPAREPART")) {
      jdbc.update("""
          INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
          VALUES (?,?,?,?,?,?,?,?)
          """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", type,
          UUID.randomUUID(), "existing-" + type, TS);
    }
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type IN ('WORK_ORDER','PREVENTIVE_ATTACHMENT','SPAREPART')",
        Long.class)).isEqualTo(3L);
  }

  @Test
  @DisplayName("12.1-DB-006 P0 sparepart_requests has expected indexes")
  void indexesExist() {
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'sparepart_requests'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_sparepart_requests_work_order", "idx_sparepart_requests_status",
            "idx_sparepart_requests_machine");
  }

  // -------------------------------------------------------------------------
  // Story 12-2 (V59): full status CHECK + timeline table
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("12.2-DB-001 P0 V59 status CHECK accepts the full FR-141 status set")
  void v59FullStatusSet() {
    for (var status : List.of("REQUESTED", "PENDING_COMPLETION", "ACKED", "PROCESSING", "READY",
        "PURCHASE_REQUESTED", "PART_RECEIVED", "PICKED_UP", "CLOSED")) {
      jdbc.update("""
          INSERT INTO sparepart_requests (id, request_type, quantity, status, requested_by, requested_at, created_at, updated_at)
          VALUES (?, 'SPAREPART', 1, ?, ?, ?, ?, ?)
          """, UUID.randomUUID(), status, UUID.randomUUID(), TS, TS, TS);
    }
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM sparepart_requests WHERE status IN ('ACKED','PROCESSING','READY','PURCHASE_REQUESTED','PART_RECEIVED','PICKED_UP','CLOSED')",
        Long.class)).isEqualTo(7L);
  }

  @Test
  @DisplayName("12.2-DB-002 P0 V59 status CHECK still rejects unknown values")
  void v59StatusRejectsUnknown() {
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO sparepart_requests (id, request_type, quantity, status, requested_by, requested_at, created_at, updated_at)
        VALUES (?, 'SPAREPART', 1, 'BOGUS', ?, ?, ?, ?)
        """, UUID.randomUUID(), UUID.randomUUID(), TS, TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("12.2-DB-003 P0 sparepart_request_timeline table exists with expected columns")
  void timelineTableExists() {
    assertThat(columnNames("sparepart_request_timeline"))
        .contains("id", "request_id", "from_status", "to_status", "actor", "action", "mre_code", "note",
            "trace_id", "created_at");
  }

  @Test
  @DisplayName("12.2-DB-004 P0 timeline FK cascades on request delete")
  void timelineCascade() {
    var requestId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO sparepart_requests (id, request_type, quantity, status, requested_by, requested_at, created_at, updated_at)
        VALUES (?, 'SPAREPART', 1, 'REQUESTED', ?, ?, ?, ?)
        """, requestId, UUID.randomUUID(), TS, TS, TS);
    jdbc.update("""
        INSERT INTO sparepart_request_timeline (id, request_id, to_status, actor, action, created_at)
        VALUES (?, ?, 'REQUESTED', ?, 'TRANSITION', ?)
        """, UUID.randomUUID(), requestId, UUID.randomUUID(), TS);
    jdbc.update("DELETE FROM sparepart_requests WHERE id = ?", requestId);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM sparepart_request_timeline WHERE request_id = ?", Long.class, requestId))
        .isEqualTo(0L);
  }

  @Test
  @DisplayName("12.2-DB-005 P0 timeline action CHECK rejects unknown values")
  void timelineActionCheck() {
    var requestId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO sparepart_requests (id, request_type, quantity, status, requested_by, requested_at, created_at, updated_at)
        VALUES (?, 'SPAREPART', 1, 'REQUESTED', ?, ?, ?, ?)
        """, requestId, UUID.randomUUID(), TS, TS, TS);
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO sparepart_request_timeline (id, request_id, to_status, actor, action, created_at)
        VALUES (?, ?, 'REQUESTED', ?, 'BOGUS', ?)
        """, UUID.randomUUID(), requestId, UUID.randomUUID(), TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("12.2-DB-006 P0 timeline has expected index")
  void timelineIndexesExist() {
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'sparepart_request_timeline'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_sparepart_request_timeline_request");
  }

  private java.util.List<String> columnNames(String table) {
    return jdbc.queryForList(
        "SELECT column_name FROM information_schema.columns "
            + "WHERE table_schema = 'public' AND table_name = ?", table)
        .stream()
        .map(row -> String.valueOf(row.get("column_name")))
        .toList();
  }
}
