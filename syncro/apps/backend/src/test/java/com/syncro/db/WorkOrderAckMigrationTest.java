package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration evidence for V67 (story 14-4): the workorder_acks table, the WORKORDER
 * scope ACK_WAITING seed and the default workorder WAHA template seeds.
 */
class WorkOrderAckMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-29T09:00:00Z"));

  @Test
  @DisplayName("14.4-DB-001 P0 workorder_acks table exists with expected columns")
  void tableExists() {
    assertThat(columnNames("workorder_acks"))
        .contains("id", "work_order_id", "acknowledged_by", "acknowledged_at", "trace_id",
            "created_at", "updated_at");
  }

  @Test
  @DisplayName("14.4-DB-002 P0 workorder_acks unique work_order_id (one ack per workorder)")
  void uniqueWorkOrderId() {
    var id = UUID.randomUUID();
    var userId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO workorder_acks (id, work_order_id, acknowledged_by, acknowledged_at)
        VALUES (?, 'WO-2608-00001', ?, ?)
        """, id, userId, TS);
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO workorder_acks (id, work_order_id, acknowledged_by, acknowledged_at)
        VALUES (?, 'WO-2608-00001', ?, ?)
        """, UUID.randomUUID(), UUID.randomUUID(), TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("14.4-DB-003 P0 WORKORDER scope ACK_WAITING seeded with 480 minutes")
  void workorderAckWaitingSeeded() {
    var count = jdbc.queryForObject(
        "SELECT count(*) FROM escalation_configs WHERE scope = 'WORKORDER' AND step = 'ACK_WAITING'",
        Long.class);
    assertThat(count).isEqualTo(1L);
    var duration = jdbc.queryForObject(
        "SELECT duration_minutes FROM escalation_configs WHERE scope = 'WORKORDER' AND step = 'ACK_WAITING'",
        Integer.class);
    assertThat(duration).isEqualTo(480);
  }

  @Test
  @DisplayName("14.4-DB-004 P0 default workorder_lifecycle + workorder_ack templates seeded")
  void workorderTemplatesSeeded() {
    var keys = jdbc.queryForList(
        "SELECT template_key FROM waha_templates WHERE template_key IN ('workorder_lifecycle','workorder_ack')");
    assertThat(keys).extracting(row -> row.get("template_key"))
        .contains("workorder_lifecycle", "workorder_ack");
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
