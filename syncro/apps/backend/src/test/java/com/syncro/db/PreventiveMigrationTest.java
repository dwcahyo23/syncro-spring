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
 * Migration evidence for V54 (story 11-1): the preventive_programs and preventive_schedules
 * tables, their CHECK constraints, the schedules unique (program_id, due_date), the FK
 * ON DELETE CASCADE, indexes, and the widened audit_log entity_type CHECK.
 */
class PreventiveMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-27T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("11.1-DB-001 P0 preventive tables exist with expected columns")
  void tablesExist() {
    assertThat(columnNames("preventive_programs"))
        .contains("id", "machine_id", "category", "schedule_type", "day_of_month", "month_of_year",
            "title", "description", "active", "created_by", "created_at", "updated_at");
    assertThat(columnNames("preventive_schedules"))
        .contains("id", "program_id", "machine_id", "due_date", "status", "completed_at", "performed_by",
            "created_at", "updated_at");
  }

  @Test
  @DisplayName("11.1-DB-001b P0 V55 preventive checklist tables exist with expected columns")
  void v55TablesExist() {
    assertThat(columnNames("preventive_checklist_results"))
        .contains("id", "schedule_id", "performed_by", "completed_at", "notes", "leader_id", "assessment",
            "approved_at", "signature_object_key", "signer_identity", "created_at", "updated_at");
    assertThat(columnNames("preventive_checklist_items"))
        .contains("id", "result_id", "position", "label", "value", "lsl", "usl", "note", "created_at");
    assertThat(columnNames("preventive_schedule_attachments"))
        .contains("id", "schedule_id", "filename", "content_type", "object_key", "size_bytes", "uploaded_by",
            "created_at", "updated_at");
  }

  @Test
  @DisplayName("11.1-DB-002 P0 preventive_programs category CHECK rejects unknown values")
  void categoryCheck() {
    var machineId = seedMachine();
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO preventive_programs (id, machine_id, category, schedule_type, day_of_month, title, created_by, created_at, updated_at)
        VALUES (?, ?, 'BOGUS', 'MONTHLY', 15, 'P', ?, ?, ?)
        """, UUID.randomUUID(), machineId, UUID.randomUUID(), TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("11.1-DB-003 P0 preventive_programs schedule_type CHECK rejects unknown values")
  void scheduleTypeCheck() {
    var machineId = seedMachine();
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO preventive_programs (id, machine_id, category, schedule_type, day_of_month, title, created_by, created_at, updated_at)
        VALUES (?, ?, 'MECHANICAL', 'WEEKLY', 15, 'P', ?, ?, ?)
        """, UUID.randomUUID(), machineId, UUID.randomUUID(), TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("11.1-DB-004 P0 preventive_schedules unique (program_id, due_date) blocks duplicates")
  void schedulePeriodUnique() {
    var machineId = seedMachine();
    var programId = seedProgram(machineId);
    jdbc.update("""
        INSERT INTO preventive_schedules (id, program_id, machine_id, due_date, status, created_at, updated_at)
        VALUES (?, ?, ?, DATE '2026-09-15', 'SCHEDULED', ?, ?)
        """, UUID.randomUUID(), programId, machineId, TS, TS);

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO preventive_schedules (id, program_id, machine_id, due_date, status, created_at, updated_at)
        VALUES (?, ?, ?, DATE '2026-09-15', 'SCHEDULED', ?, ?)
        """, UUID.randomUUID(), programId, machineId, TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("11.1-DB-005 P0 preventive_schedules status CHECK rejects unknown values")
  void scheduleStatusCheck() {
    var machineId = seedMachine();
    var programId = seedProgram(machineId);
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO preventive_schedules (id, program_id, machine_id, due_date, status, created_at, updated_at)
        VALUES (?, ?, ?, DATE '2026-10-01', 'BOGUS', ?, ?)
        """, UUID.randomUUID(), programId, machineId, TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("11.1-DB-006 P1 schedules cascade-delete when the program is removed")
  void schedulesCascadeOnProgramDelete() {
    var machineId = seedMachine();
    var programId = seedProgram(machineId);
    var scheduleId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO preventive_schedules (id, program_id, machine_id, due_date, status, created_at, updated_at)
        VALUES (?, ?, ?, DATE '2026-09-15', 'SCHEDULED', ?, ?)
        """, scheduleId, programId, machineId, TS, TS);

    jdbc.update("DELETE FROM preventive_programs WHERE id = ?", programId);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM preventive_schedules WHERE id = ?", Long.class, scheduleId)).isZero();
  }

  @Test
  @DisplayName("11.1-DB-007 P0 preventive tables have the expected indexes")
  void indexesExist() {
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'preventive_programs'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_preventive_programs_machine");
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'preventive_schedules'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_preventive_schedules_status_due", "idx_preventive_schedules_machine");
  }

  @Test
  @DisplayName("11.1-DB-008 P0 audit_log entity_type CHECK accepts PREVENTIVE_PROGRAM and PREVENTIVE_SCHEDULE")
  void auditEntityTypeAcceptsPreventive() {
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "PREVENTIVE_PROGRAM",
        UUID.randomUUID(), "P", TS);
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "PREVENTIVE_SCHEDULE",
        UUID.randomUUID(), "due 2026-09-15", TS);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type IN ('PREVENTIVE_PROGRAM','PREVENTIVE_SCHEDULE')",
        Long.class)).isEqualTo(2L);
  }

  @Test
  @DisplayName("11.2-DB-001 P0 V55 checklist_results unique constraint per schedule")
  void checklistResultUniquePerSchedule() {
    var machineId = seedMachine();
    var programId = seedProgram(machineId);
    var scheduleId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO preventive_schedules (id, program_id, machine_id, due_date, status, created_at, updated_at)
        VALUES (?, ?, ?, DATE '2026-09-15', 'SCHEDULED', ?, ?)
        """, scheduleId, programId, machineId, TS, TS);
    jdbc.update("""
        INSERT INTO preventive_checklist_results (id, schedule_id, performed_by, completed_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), scheduleId, UUID.randomUUID(), TS, TS, TS);

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO preventive_checklist_results (id, schedule_id, performed_by, completed_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), scheduleId, UUID.randomUUID(), TS, TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("11.2-DB-002 P0 V55 audit_log entity_type accepts PREVENTIVE_CHECKLIST and PREVENTIVE_ATTACHMENT")
  void auditEntityTypeAcceptsNewTypes() {
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "PREVENTIVE_CHECKLIST",
        UUID.randomUUID(), "P due 2026-09-15", TS);
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "PREVENTIVE_ATTACHMENT",
        UUID.randomUUID(), "schedule abc", TS);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type IN ('PREVENTIVE_CHECKLIST','PREVENTIVE_ATTACHMENT')",
        Long.class)).isEqualTo(2L);
  }

  @Test
  @DisplayName("11.2-DB-003 P0 V55 attachments size_bytes non-negative CHECK")
  void attachmentSizeNonNegative() {
    var machineId = seedMachine();
    var programId = seedProgram(machineId);
    var scheduleId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO preventive_schedules (id, program_id, machine_id, due_date, status, created_at, updated_at)
        VALUES (?, ?, ?, DATE '2026-09-15', 'SCHEDULED', ?, ?)
        """, scheduleId, programId, machineId, TS, TS);

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO preventive_schedule_attachments (id, schedule_id, filename, content_type, object_key, size_bytes, uploaded_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), scheduleId, "f.jpg", "image/jpeg", "k", -1, UUID.randomUUID(), TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("11.2-DB-004 P0 V55 tables have expected indexes")
  void v55IndexesExist() {
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'preventive_checklist_results'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_preventive_checklist_schedule");
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'preventive_checklist_items'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_preventive_checklist_items_result");
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'preventive_schedule_attachments'"))
        .extracting(row -> row.get("indexname"))
        .contains("idx_preventive_schedule_attachments_schedule");
  }

  @Test
  @DisplayName("11.3-DB-001 P0 V56 adds auto_workorder to preventive_programs and preventive_schedule_id to work_orders")
  void v56Columns() {
    assertThat(columnNames("preventive_programs")).contains("auto_workorder");
    assertThat(columnNames("work_orders")).contains("preventive_schedule_id");
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'work_orders'"))
        .extracting(row -> row.get("indexname"))
        .contains("uq_work_orders_preventive_schedule", "idx_work_orders_preventive_schedule");
  }

  @Test
  @DisplayName("11.3-DB-002 P0 V56 seeds category 02 Preventive idempotently")
  void v56Category02Seeded() {
    var count = jdbc.queryForObject(
        "SELECT count(*) FROM work_order_categories WHERE code = '02'", Long.class);
    assertThat(count).isEqualTo(1L);

    // Idempotent: re-running the ON CONFLICT DO NOTHING insert must not duplicate.
    jdbc.update("""
        INSERT INTO work_order_categories (id, code, label, created_by, created_at, updated_at)
        VALUES (gen_random_uuid(), '02', 'Preventive', NULL, ?, ?)
        ON CONFLICT (code) DO NOTHING
        """, TS, TS);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM work_order_categories WHERE code = '02'", Long.class)).isEqualTo(1L);
  }

  @Test
  @DisplayName("11.3-DB-003 P0 V56 unique preventive_schedule_id rejects a second workorder for the same schedule")
  void v56UniquePreventiveSchedule() {
    var machineId = seedMachine();
    var programId = seedProgram(machineId);
    var scheduleId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO preventive_schedules (id, program_id, machine_id, due_date, status, created_at, updated_at)
        VALUES (?, ?, ?, DATE '2026-09-15', 'PERFORMED', ?, ?)
        """, scheduleId, programId, machineId, TS, TS);

    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, sync_version, preventive_schedule_id, created_at, updated_at)
        VALUES ('WO-2609-00001', 'INTERNAL', 'OPEN', ?, 0, ?, ?, ?)
        """, machineId, scheduleId, TS, TS);

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, sync_version, preventive_schedule_id, created_at, updated_at)
        VALUES ('WO-2609-00002', 'INTERNAL', 'OPEN', ?, 0, ?, ?, ?)
        """, machineId, scheduleId, TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("11.3-DB-004 P0 V56 FK ON DELETE SET NULL nulls preventive_schedule_id on schedule delete")
  void v56FkOnDeleteSetNull() {
    var machineId = seedMachine();
    var programId = seedProgram(machineId);
    var scheduleId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO preventive_schedules (id, program_id, machine_id, due_date, status, created_at, updated_at)
        VALUES (?, ?, ?, DATE '2026-09-15', 'PERFORMED', ?, ?)
        """, scheduleId, programId, machineId, TS, TS);
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, sync_version, preventive_schedule_id, created_at, updated_at)
        VALUES ('WO-2609-00010', 'INTERNAL', 'OPEN', ?, 0, ?, ?, ?)
        """, machineId, scheduleId, TS, TS);

    jdbc.update("DELETE FROM preventive_schedules WHERE id = ?", scheduleId);

    assertThat(jdbc.queryForObject(
        "SELECT preventive_schedule_id FROM work_orders WHERE id = 'WO-2609-00010'", Object.class)).isNull();
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

  private UUID seedProgram(UUID machineId) {
    var id = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO preventive_programs (id, machine_id, category, schedule_type, day_of_month, title, created_by, created_at, updated_at)
        VALUES (?, ?, 'MECHANICAL', 'MONTHLY', 15, 'P', ?, ?, ?)
        """, id, machineId, UUID.randomUUID(), TS, TS);
    return id;
  }
}