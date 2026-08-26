package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
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
 * Migration evidence for V51 (story 10-6): the 11 additive report/CP-CPK/FMEA/stop-time
 * columns on work_orders plus the stop_time_reason and fmea_failure_type CHECK
 * constraints. No new table, no audit_log entity_type change (report writes audit under
 * the existing WORK_ORDER type).
 */
class WorkorderReportsMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-26T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("10.6-DB-001 P0 work_orders gains the 11 report columns")
  void reportColumnsExist() {
    assertThat(columnNames("work_orders"))
        .contains("report_chronological", "report_analyze", "report_corrective", "report_preventive",
            "cp_cp_lower", "cp_cp_upper", "cpk", "cpk_pdf_object_key", "fmea_failure_type",
            "stop_time_reason", "stop_time_detail");
  }

  @Test
  @DisplayName("10.6-DB-002 P0 report columns are nullable — CP/CPK is never mandatory")
  void reportColumnsNullable() {
    var machineId = seedMachine();
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, "WO-2409-NULLABLE", machineId, TS, TS);
    var count = jdbc.queryForObject(
        "SELECT count(*) FROM work_orders WHERE id = 'WO-2409-NULLABLE'"
            + " AND report_chronological IS NULL AND cpk IS NULL AND stop_time_reason IS NULL",
        Long.class);
    assertThat(count).isEqualTo(1L);
  }

  @Test
  @DisplayName("10.6-DB-003 P0 stop_time_reason CHECK accepts each enum value and NULL")
  void stopTimeReasonCheckAcceptsValidValues() {
    var machineId = seedMachine();
    var id = "WO-2409-ST-" + seedSeq.incrementAndGet();
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, id, machineId, TS, TS);
    jdbc.update("""
        UPDATE work_orders SET stop_time_reason = ? WHERE id = ?
        """, "ELECTRIC", id);
    jdbc.update("""
        UPDATE work_orders SET stop_time_reason = ? WHERE id = ?
        """, "MECHANICAL", id);
    jdbc.update("""
        UPDATE work_orders SET stop_time_reason = ? WHERE id = ?
        """, "PNEUMATIC", id);
    jdbc.update("""
        UPDATE work_orders SET stop_time_reason = ? WHERE id = ?
        """, "HYDRAULIC", id);
    jdbc.update("""
        UPDATE work_orders SET stop_time_reason = ? WHERE id = ?
        """, "OTHER", id);
    // NULL allowed (the gate runs in-service, the CHECK only constrains non-null values).
    jdbc.update("""
        UPDATE work_orders SET stop_time_reason = NULL WHERE id = ?
        """, id);
    assertThat(jdbc.queryForObject("SELECT stop_time_reason FROM work_orders WHERE id = ?", String.class, id))
        .isNull();
  }

  @Test
  @DisplayName("10.6-DB-004 P0 stop_time_reason CHECK rejects an unknown value")
  void stopTimeReasonCheckRejectsUnknown() {
    var machineId = seedMachine();
    var id = "WO-2409-ST-BAD";
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, id, machineId, TS, TS);

    assertThatThrownBy(() -> jdbc.update("""
        UPDATE work_orders SET stop_time_reason = ? WHERE id = ?
        """, "CUSTOM", id))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.6-DB-005 P0 CP/CPK values persist with NUMERIC(8,4) precision")
  void cpkValuesPersist() {
    var machineId = seedMachine();
    var id = "WO-2409-CPK";
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at,
          report_chronological, report_analyze, report_corrective, report_preventive,
          cp_cp_lower, cp_cp_upper, cpk, cpk_pdf_object_key, fmea_failure_type,
          stop_time_reason, stop_time_detail)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, id, machineId, TS, TS,
        "Chronology", "Analyze", "Corrective", "Preventive",
        new BigDecimal("1.1000"), new BigDecimal("1.2000"), new BigDecimal("1.3300"),
        "workorders/" + id + "/cpk/uuid.pdf", "MECHANICAL", "ELECTRIC", "Bearing worn");

    var values = jdbc.queryForMap(
        "SELECT report_chronological, cp_cp_lower, cpk, cpk_pdf_object_key, fmea_failure_type,"
            + " stop_time_reason, stop_time_detail FROM work_orders WHERE id = ?", id);
    assertThat(values.get("report_chronological")).isEqualTo("Chronology");
    assertThat((BigDecimal) values.get("cp_cp_lower")).isEqualByComparingTo(new BigDecimal("1.1000"));
    assertThat((BigDecimal) values.get("cpk")).isEqualByComparingTo(new BigDecimal("1.3300"));
    assertThat(values.get("cpk_pdf_object_key")).isEqualTo("workorders/" + id + "/cpk/uuid.pdf");
    assertThat(values.get("fmea_failure_type")).isEqualTo("MECHANICAL");
    assertThat(values.get("stop_time_reason")).isEqualTo("ELECTRIC");
    assertThat(values.get("stop_time_detail")).isEqualTo("Bearing worn");
  }

  @Test
  @DisplayName("10.6-DB-007 P0 fmea_failure_type CHECK accepts each enum value and rejects an unknown")
  void fmeaFailureTypeCheck() {
    var machineId = seedMachine();
    var id = "WO-2409-FMEA";
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, id, machineId, TS, TS);
    for (var value : new String[] {"ELECTRIC", "MECHANICAL", "PNEUMATIC", "HYDRAULIC", "OTHER"}) {
      jdbc.update("UPDATE work_orders SET fmea_failure_type = ? WHERE id = ?", value, id);
    }
    jdbc.update("UPDATE work_orders SET fmea_failure_type = NULL WHERE id = ?", id);
    assertThatThrownBy(() -> jdbc.update("UPDATE work_orders SET fmea_failure_type = ? WHERE id = ?",
        "CUSTOM", id))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.6-DB-006 P0 audit_log entity_type still accepts WORK_ORDER for report writes")
  void auditEntityTypeAcceptsWorkOrder() {
    var machineId = seedMachine();
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?, ?, ?)
        """, "WO-2409-00001", machineId, TS, TS);
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
          plant_id, previous_value, new_value, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "tech@syncro.dev", "UPDATE", "WORK_ORDER",
        UUID.nameUUIDFromBytes("WO-2409-00001".getBytes()), "WO-2409-00001", null, null, "{\"cpk\":1.5}", TS);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'WORK_ORDER' AND entity_label = 'WO-2409-00001'",
        Long.class)).isEqualTo(1L);
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
