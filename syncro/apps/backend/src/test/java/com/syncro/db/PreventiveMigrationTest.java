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