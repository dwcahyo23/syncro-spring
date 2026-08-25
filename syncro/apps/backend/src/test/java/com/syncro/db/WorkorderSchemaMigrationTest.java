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
 * Migration evidence for V47 (story 10-1): the four workorder tables exist with the
 * pinned columns, category code uniqueness, work_orders source/status CHECKs and the
 * self-parent FK, the status-history index, and the sequence table shape. Also proves
 * the audit_log entity_type CHECK accepts WORK_ORDER_CATEGORY.
 */
class WorkorderSchemaMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-26T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("10.1-DB-001 P0 all four workorder tables exist with pinned columns")
  void workOrderTablesExistWithPinnedColumns() {
    assertThat(columnNames("work_orders"))
        .contains("id", "source", "parent_id", "status", "category_id", "machine_id",
            "description", "sync_version", "created_by", "created_at", "updated_at");
    assertThat(columnNames("work_order_categories"))
        .contains("id", "code", "label", "created_by", "created_at", "updated_at");
    assertThat(columnNames("work_order_status_history"))
        .contains("id", "work_order_id", "from_status", "to_status", "source", "actor",
            "trace_id", "transitioned_at");
    assertThat(columnNames("workorder_id_sequences"))
        .contains("prefix", "last_seq", "updated_at");
  }

  @Test
  @DisplayName("10.1-DB-002 P0 work_order_categories code uniqueness is enforced")
  void categoryCodeUniqueEnforced() {
    insertCategory("01", "Breakdown");
    assertThatThrownBy(() -> insertCategory("01", "Duplicate"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.1-DB-003 P0 work_orders source CHECK accepts SYNCED/INTERNAL and rejects others")
  void workOrderSourceCheckEnforced() {
    var machineId = seedMachine();
    insertWorkOrder("WO-2409-00001", "SYNCED", "OPEN", machineId);
    insertWorkOrder("WO-2409-00002", "INTERNAL", "OPEN", machineId);
    assertThatThrownBy(() -> insertWorkOrder("WO-2409-00003", "EXTERNAL", "OPEN", machineId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.1-DB-004 P0 work_orders status CHECK accepts the eight statuses and rejects others")
  void workOrderStatusCheckEnforced() {
    var machineId = seedMachine();
    for (var status : new String[] {"DRAFT", "OPEN", "ASSIGNED", "IN_PROGRESS", "ON_PROCUREMENT",
        "DONE", "CLOSED", "CANCELLED"}) {
      insertWorkOrder("WO-2409-" + status, "INTERNAL", status, machineId);
    }
    assertThatThrownBy(() -> insertWorkOrder("WO-2409-BAD", "INTERNAL", "PAUSED", machineId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.1-DB-005 P0 work_orders.parent_id self-FK is enforced")
  void workOrderParentForeignKeyEnforced() {
    var machineId = seedMachine();
    insertWorkOrder("WO-2409-PARENT", "INTERNAL", "OPEN", machineId);
    jdbc.update("""
        INSERT INTO work_orders (id, source, parent_id, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', ?, 'OPEN', ?, ?, ?)
        """, "WO-2409-CHILD", "WO-2409-PARENT", machineId, TS, TS);

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO work_orders (id, source, parent_id, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', ?, 'OPEN', ?, ?, ?)
        """, "WO-2409-ORPHAN", "WO-2409-NOPE", machineId, TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.1-DB-006 P0 workorder_id_sequences shape: prefix PK, last_seq NOT NULL")
  void workOrderIdSequencesShape() {
    var nullable = jdbc.queryForObject(
        "SELECT is_nullable FROM information_schema.columns "
            + "WHERE table_name = 'workorder_id_sequences' AND column_name = 'last_seq'",
        String.class);
    assertThat(nullable).as("last_seq must be NOT NULL").isEqualTo("NO");
    jdbc.update("INSERT INTO workorder_id_sequences (prefix, last_seq, updated_at) VALUES (?,?,?)",
        "2409", 0, TS);
    assertThat(jdbc.queryForObject(
        "SELECT last_seq FROM workorder_id_sequences WHERE prefix = '2409'", Integer.class))
        .isEqualTo(0);
  }

  @Test
  @DisplayName("10.1-DB-007 P0 status history has an index on work_order_id")
  void statusHistoryWorkOrderIndexExists() {
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'work_order_status_history'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_work_order_status_history_work_order_id");
  }

  @Test
  @DisplayName("10.1-DB-008 P1 audit_log entity_type CHECK accepts WORK_ORDER_CATEGORY")
  void auditEntityTypeAcceptsWorkOrderCategory() {
    var categoryId = insertCategory("02", "Preventive");
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "WORK_ORDER_CATEGORY",
        categoryId, "02", null, null, null, TS);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'WORK_ORDER_CATEGORY' AND entity_id = ?",
        Long.class, categoryId)).isEqualTo(1L);
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

  private void insertWorkOrder(String id, String source, String status, UUID machineId) {
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?,?,?,?,?,?)
        """, id, source, status, machineId, TS, TS);
  }

  private UUID insertCategory(String code, String label) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO work_order_categories (id, code, label, created_at, updated_at)
        VALUES (?,?,?,?,?)
        """, id, code, label, TS, TS);
    return id;
  }
}
