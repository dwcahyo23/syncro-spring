package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration evidence for V50 (story 10-5): the workorder_attachments table, columns,
 * FK, index, and the widened audit_log entity_type CHECK that includes
 * WORKORDER_ATTACHMENT.
 */
class WorkorderAttachmentsMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-26T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("10.5-DB-001 P0 workorder_attachments table exists with expected columns")
  void tableExists() {
    assertThat(columnNames("workorder_attachments"))
        .contains("id", "work_order_id", "filename", "content_type", "object_key", "size_bytes",
            "uploaded_by", "created_at", "updated_at");
  }

  @Test
  @DisplayName("10.5-DB-002 P0 workorder_attachments FK to work_orders is enforced")
  void foreignKeyEnforced() {
    var machineId = seedMachine();
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, "WO-2409-00001", machineId, TS, TS);
    // Valid insert: parent exists.
    jdbc.update("""
        INSERT INTO workorder_attachments (id, work_order_id, filename, content_type, object_key, size_bytes, uploaded_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), "WO-2409-00001", "photo.jpg", "image/jpeg", "key/1.jpg", 100, UUID.randomUUID(), TS);
    // Invalid insert: non-existent workorder → FK violation.
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO workorder_attachments (id, work_order_id, filename, content_type, object_key, size_bytes, uploaded_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), "WO-2409-NOPE", "photo.jpg", "image/jpeg", "key/2.jpg", 100, UUID.randomUUID(), TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.5-DB-003 P0 workorder_attachments CK rejects negative size_bytes")
  void sizeNonNegativeCheck() {
    var machineId = seedMachine();
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, "WO-2409-CK", machineId, TS, TS);

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO workorder_attachments (id, work_order_id, filename, content_type, object_key, size_bytes, uploaded_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), "WO-2409-CK", "photo.jpg", "image/jpeg", "key/3.jpg", -1, UUID.randomUUID(), TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.5-DB-004 P0 workorder_attachments has an index on work_order_id")
  void indexExists() {
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'workorder_attachments'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_workorder_attachments_work_order_id");
  }

  @Test
  @DisplayName("10.5-DB-005 P0 audit_log entity_type CHECK accepts WORKORDER_ATTACHMENT")
  void auditEntityTypeAcceptsWorkorderAttachment() {
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "WORKORDER_ATTACHMENT",
        UUID.randomUUID(), "WO-2409-00001", TS);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'WORKORDER_ATTACHMENT'",
        Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("10.5-DB-006 P1 audit_log entity_type CHECK still rejects unknown types")
  void auditEntityTypeStillRejectsUnknown() {
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "BOGUS_TYPE",
        UUID.randomUUID(), "label", TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private java.util.List<String> columnNames(String table) {
    return jdbc.queryForList(
        "SELECT column_name FROM information_schema.columns "
            + "WHERE table_schema = 'public' AND table_name = ?", table)
        .stream()
        .map(row -> String.valueOf(row.get("column_name")))
        .toList();
  }

  private UUID seedPlant() {
    UUID plantId = UUID.randomUUID();
    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        plantId, "P-" + seedSeq.incrementAndGet(), "Plant", TS, TS);
    return plantId;
  }

  private UUID seedMachine() {
    var plantId = seedPlant();
    var groupId = UUID.randomUUID();
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        groupId, plantId, "G-" + seedSeq.incrementAndGet(), TS, TS);
    var machineId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, machineId, plantId, groupId, "M-" + seedSeq.incrementAndGet(), "Machine", "ACTIVE", TS, TS);
    return machineId;
  }
}