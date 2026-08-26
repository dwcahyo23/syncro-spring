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
 * Migration evidence for V52 (story 10-7): the workorder_todos table, columns, FK
 * (ON DELETE CASCADE), status CHECK, indexes, and the widened audit_log entity_type
 * CHECK that includes WORK_ORDER_TODO.
 */
class WorkorderTodosMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-26T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("10.7-DB-001 P0 workorder_todos table exists with expected columns")
  void tableExists() {
    assertThat(columnNames("workorder_todos"))
        .contains("id", "workorder_id", "title", "description", "assigned_technician_id",
            "status", "sort_order", "created_by", "created_at", "updated_at", "completed_at");
  }

  @Test
  @DisplayName("10.7-DB-002 P0 workorder_todos FK to work_orders rejects non-existent workorder")
  void foreignKeyRejectsInvalid() {
    var machineId = seedMachine();
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, "WO-2409-FK", machineId, TS, TS);

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO workorder_todos (id, workorder_id, title, status, sort_order, created_by, created_at, updated_at)
        VALUES (?, ?, ?, 'PENDING', 0, ?, ?, ?)
        """, UUID.randomUUID(), "WO-2409-NOPE", "Fix", UUID.randomUUID(), TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.7-DB-002b P0 workorder_todos FK cascades delete from work_orders")
  void foreignKeyCascades() {
    var machineId = seedMachine();
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, "WO-2409-CAS", machineId, TS, TS);
    var todoId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO workorder_todos (id, workorder_id, title, status, sort_order, created_by, created_at, updated_at)
        VALUES (?, ?, ?, 'PENDING', 0, ?, ?, ?)
        """, todoId, "WO-2409-CAS", "Fix bearing", UUID.randomUUID(), TS, TS);

    jdbc.update("DELETE FROM work_orders WHERE id = 'WO-2409-CAS'");
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM workorder_todos WHERE id = ?", Long.class, todoId)).isZero();
  }

  @Test
  @DisplayName("10.7-DB-003 P0 workorder_todos status CHECK accepts valid statuses and rejects unknown")
  void statusCheck() {
    var machineId = seedMachine();
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, "WO-2409-CK", machineId, TS, TS);
    for (var status : java.util.List.of("PENDING", "IN_PROGRESS", "COMPLETED", "CANCELLED")) {
      jdbc.update("""
          INSERT INTO workorder_todos (id, workorder_id, title, status, sort_order, created_by, created_at, updated_at)
          VALUES (?, ?, ?, ?, 0, ?, ?, ?)
          """, UUID.randomUUID(), "WO-2409-CK", "Task " + status, status, UUID.randomUUID(), TS, TS);
    }
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO workorder_todos (id, workorder_id, title, status, sort_order, created_by, created_at, updated_at)
        VALUES (?, ?, ?, 'BOGUS', 0, ?, ?, ?)
        """, UUID.randomUUID(), "WO-2409-CK", "Bad", UUID.randomUUID(), TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.7-DB-004 P0 workorder_todos defaults PENDING status and sort_order 0")
  void defaultsApplied() {
    var machineId = seedMachine();
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, "WO-2409-DEF", machineId, TS, TS);
    jdbc.update("""
        INSERT INTO workorder_todos (id, workorder_id, title, created_by)
        VALUES (?, ?, ?, ?)
        """, UUID.randomUUID(), "WO-2409-DEF", "Task", UUID.randomUUID());

    var row = jdbc.queryForMap(
        "SELECT status, sort_order FROM workorder_todos WHERE workorder_id = 'WO-2409-DEF'");
    assertThat(String.valueOf(row.get("status"))).isEqualTo("PENDING");
    assertThat(((Number) row.get("sort_order")).intValue()).isZero();
  }

  @Test
  @DisplayName("10.7-DB-005 P0 workorder_todos has indexes on workorder_id and assigned_technician_id")
  void indexesExist() {
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'workorder_todos'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_workorder_todos_workorder_id", "idx_workorder_todos_assigned_tech");
  }

  @Test
  @DisplayName("10.7-DB-006 P0 audit_log entity_type CHECK accepts WORK_ORDER_TODO")
  void auditEntityTypeAcceptsWorkOrderTodo() {
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "WORK_ORDER_TODO",
        UUID.randomUUID(), "Fix bearing", TS);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'WORK_ORDER_TODO'",
        Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("10.7-DB-007 P1 audit_log entity_type CHECK still rejects unknown types")
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