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
 * Migration evidence for V49 (story 10-4): the repair_sessions table, the EXCLUDE
 * overlap constraint, the work_orders MTTR/response_time/done_reason columns, the
 * categories target_response_minutes column, and the widened audit_log entity_type
 * CHECK that includes REPAIR_SESSION.
 */
class RepairSessionsMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-26T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("10.4-DB-001 P0 repair_sessions table exists with expected columns")
  void repairSessionsTableExists() {
    assertThat(columnNames("repair_sessions"))
        .contains("id", "work_order_id", "technician_id", "description", "started_at", "ended_at",
            "duration_minutes", "created_at", "updated_at");
  }

  @Test
  @DisplayName("10.4-DB-002 P0 repair_sessions FK to work_orders is enforced")
  void repairSessionForeignKeyEnforced() {
    var machineId = seedMachine();
    // First insert a valid workorder + session (proves FK works when parent exists).
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, "WO-2409-00001", machineId, TS, TS);
    jdbc.update("""
        INSERT INTO repair_sessions (id, work_order_id, technician_id, started_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), "WO-2409-00001", UUID.randomUUID(), TS, TS, TS);
    // Then try to insert session with non-existent workorder → FK violation.
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO repair_sessions (id, work_order_id, technician_id, started_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), "WO-2409-NOPE", UUID.randomUUID(), TS, TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.4-DB-003 P0 repair_sessions EXCLUDE constraint prevents overlap")
  void repairSessionExcludeConstraintEnforced() {
    var machineId = seedMachine();
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, "WO-2409-OVL", machineId, TS, TS);

    var t1 = Timestamp.from(Instant.parse("2026-08-26T10:00:00Z"));
    var t2 = Timestamp.from(Instant.parse("2026-08-26T11:00:00Z"));
    var t3 = Timestamp.from(Instant.parse("2026-08-26T10:30:00Z"));

    // First session 10:00-11:00
    jdbc.update("""
        INSERT INTO repair_sessions (id, work_order_id, technician_id, started_at, ended_at, duration_minutes, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 60, ?, ?)
        """, UUID.randomUUID(), "WO-2409-OVL", UUID.randomUUID(), t1, t2, TS, TS);

    // Overlapping session 10:30-11:30 should be rejected by the EXCLUDE constraint.
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO repair_sessions (id, work_order_id, technician_id, started_at, ended_at, duration_minutes, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 60, ?, ?)
        """, UUID.randomUUID(), "WO-2409-OVL", UUID.randomUUID(), t3, Timestamp.from(Instant.parse("2026-08-26T11:30:00Z")), TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.4-DB-004 P0 repair_sessions CK rejects ended_at <= started_at")
  void repairSessionEndAfterStartCheck() {
    var machineId = seedMachine();
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, "WO-2409-CK", machineId, TS, TS);

    var t1 = Timestamp.from(Instant.parse("2026-08-26T10:00:00Z"));
    var t2 = Timestamp.from(Instant.parse("2026-08-26T10:00:00Z"));

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO repair_sessions (id, work_order_id, technician_id, started_at, ended_at, duration_minutes, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 0, ?, ?)
        """, UUID.randomUUID(), "WO-2409-CK", UUID.randomUUID(), t1, t2, TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.4-DB-005 P0 work_orders gains mttr_minutes, response_time_minutes, done_reason")
  void workOrdersMttrColumnsExist() {
    assertThat(columnNames("work_orders"))
        .contains("mttr_minutes", "response_time_minutes", "done_reason");
  }

  @Test
  @DisplayName("10.4-DB-006 P0 work_order_categories gains target_response_minutes")
  void workOrderCategoriesTargetColumnExists() {
    assertThat(columnNames("work_order_categories"))
        .contains("target_response_minutes");
  }

  @Test
  @DisplayName("10.4-DB-007 P0 audit_log entity_type CHECK accepts REPAIR_SESSION")
  void auditEntityTypeAcceptsRepairSession() {
    var machineId = seedMachine();
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, "WO-2409-00001", machineId, TS, TS);
    jdbc.update("""
        INSERT INTO repair_sessions (id, work_order_id, technician_id, started_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), "WO-2409-00001", UUID.randomUUID(), TS, TS, TS);

    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "REPAIR_SESSION",
        UUID.randomUUID(), "WO-2409-00001", null, null, null, TS);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'REPAIR_SESSION' AND entity_label = 'WO-2409-00001'",
        Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("10.4-DB-008 P1 audit_log entity_type CHECK still rejects unknown types")
  void auditEntityTypeStillRejectsUnknown() {
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "UNKNOWN_TYPE",
        UUID.randomUUID(), "label", null, null, null, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.4-DB-009 P0 repair_sessions has an index on work_order_id")
  void repairSessionIndexExists() {
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'repair_sessions'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_repair_sessions_work_order_id");
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