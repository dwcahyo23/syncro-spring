package com.syncro.maintenance.application;

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
 * Schema-level evidence re-anchored to the blueprint I1 tables (story 15-1, was V66):
 * {@code signature_uses} with the per-workorder partial unique constraint
 * {@code uq_signature_uses_work_order} (subject_type='WORK_ORDER') and the audit_log
 * entity_type CHECK accepting WORKORDER_SIGNATURE. The JPA wiring is covered by the
 * unit tests; this integration verifies the schema-level invariants against the real
 * Postgres container.
 */
class WorkorderPrintReportServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-28T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("15.1 signature_uses carries the pinned columns")
  void signatureUsesTableExistsWithPinnedColumns() {
    var columns = jdbc.queryForList("""
        SELECT column_name, is_nullable FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'signature_uses'
        """);
    assertThat(columns).extracting(row -> row.get("column_name"))
        .contains("id", "signer_id", "signature_id", "module", "subject_type", "subject_id",
            "action", "signature_object_key", "signed_at", "created_at");
  }

  @Test
  @DisplayName("15.1 enforces one WORK_ORDER signature per subject id")
  void oneSignaturePerWorkorderEnforced() {
    var machineId = seedMachine();
    var woId = seedWorkOrder(machineId);

    jdbc.update("""
        INSERT INTO signature_uses (id, signer_id, module, subject_type, subject_id, action,
          signature_object_key, signed_at, created_at)
        VALUES (?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "maintenance", "WORK_ORDER", woId,
        "APPROVE_WORKORDER", "workorders/" + woId + "/signature/a.png", TS, TS);

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO signature_uses (id, signer_id, module, subject_type, subject_id, action,
          signature_object_key, signed_at, created_at)
        VALUES (?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "maintenance", "WORK_ORDER", woId,
        "APPROVE_WORKORDER", "workorders/" + woId + "/signature/b.png", TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("15.1 settings singleton row exists")
  void settingsRowExists() {
    assertThat(jdbc.queryForObject("SELECT count(*) FROM settings", Long.class)).isEqualTo(1L);
    var key = jdbc.queryForObject("SELECT logo_object_key FROM settings", String.class);
    assertThat(key).isNull();
  }

  @Test
  @DisplayName("15.1 audit_log entity_type CHECK accepts WORKORDER_SIGNATURE")
  void auditEntityTypeAcceptsWorkorderSignature() {
    UUID actorId = UUID.randomUUID();
    UUID sigId = UUID.randomUUID();
    var machineId = seedMachine();
    var plantId = jdbc.queryForObject("SELECT plant_id FROM machines WHERE id = ?", UUID.class, machineId);

    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), actorId, "audit-actor", "CREATE", "WORKORDER_SIGNATURE", sigId, "sig",
        plantId, null, null, TS);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'WORKORDER_SIGNATURE' AND entity_id = ?",
        Long.class, sigId)).isEqualTo(1L);
  }

  @Test
  @DisplayName("15.1 audit_log entity_type CHECK still rejects unknown types")
  void auditEntityTypeRejectsUnknown() {
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "NOT_A_TYPE", UUID.randomUUID(),
        "x", null, null, null, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private UUID seedMachine() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        plantId, "P-" + seedSeq.incrementAndGet(), "Plant", TS, TS);
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        groupId, plantId, "Group " + seedSeq.incrementAndGet(), TS, TS);
    jdbc.update("""
        INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, machineId, plantId, groupId, "M-" + seedSeq.incrementAndGet(), "Machine",
        "ACTIVE", TS, TS);
    return machineId;
  }

  private String seedWorkOrder(UUID machineId) {
    var woId = "WO-2609-" + String.format("%05d", seedSeq.incrementAndGet());
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, sync_version, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?)
        """, woId, "INTERNAL", "PENDING_REVIEW", machineId, 0L, TS, TS);
    return woId;
  }
}
