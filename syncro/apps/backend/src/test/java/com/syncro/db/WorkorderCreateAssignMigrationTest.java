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
 * Migration evidence for V48 (story 10-2): the additive create/assign columns
 * (idempotency_key + assigned_technician_id) exist on work_orders with the
 * idempotency index, and the audit_log entity_type CHECK accepts WORK_ORDER while
 * still rejecting unknown types.
 */
class WorkorderCreateAssignMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-26T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("10.2-DB-001 P0 work_orders gains idempotency_key and assigned_technician_id")
  void workOrderCreateAssignColumnsExist() {
    assertThat(columnNames("work_orders"))
        .contains("idempotency_key", "assigned_technician_id");

    var machineId = seedMachine();
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, idempotency_key, assigned_technician_id,
          created_at, updated_at)
        VALUES (?, 'INTERNAL', 'OPEN', ?, ?, ?, ?, ?)
        """, "WO-2409-00001", machineId, "key-123", UUID.randomUUID(), TS, TS);

    assertThat(jdbc.queryForObject(
        "SELECT idempotency_key FROM work_orders WHERE id = 'WO-2409-00001'", String.class))
        .isEqualTo("key-123");
  }

  @Test
  @DisplayName("10.2-DB-002 P0 work_orders idempotency index exists")
  void workOrderIdempotencyIndexExists() {
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'work_orders'"))
        .extracting(row -> row.get("indexname"))
        .contains("uq_work_orders_idempotency_key");
  }

  @Test
  @DisplayName("10.2-DB-003 P0 audit_log entity_type CHECK accepts WORK_ORDER")
  void auditEntityTypeAcceptsWorkOrder() {
    var machineId = seedMachine();
    var workOrderId = "WO-2409-00001";
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'OPEN', ?, ?, ?)
        """, workOrderId, machineId, TS, TS);

    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "WORK_ORDER",
        UUID.nameUUIDFromBytes(workOrderId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        workOrderId, null, null, null, TS);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'WORK_ORDER' AND entity_label = ?",
        Long.class, workOrderId)).isEqualTo(1L);
  }

  @Test
  @DisplayName("10.2-DB-004 P1 audit_log entity_type CHECK still rejects unknown types")
  void auditEntityTypeStillRejectsUnknown() {
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "UNKNOWN_TYPE",
        UUID.randomUUID(), "label", null, null, null, TS))
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
