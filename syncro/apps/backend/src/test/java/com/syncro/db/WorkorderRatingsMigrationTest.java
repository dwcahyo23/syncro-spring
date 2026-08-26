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
 * Migration evidence for V53 (story 10-8): the rating_dimensions, workorder_ratings and
 * workorder_rating_scores tables, their constraints (type CHECK, score range, FKs,
 * unique identity + partial WORKORDER unique), the seeded default dimensions, and the
 * widened audit_log entity_type CHECK including WORKORDER_RATING and RATING_DIMENSION.
 */
class WorkorderRatingsMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-26T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("10.8-DB-001 P0 rating tables exist with expected columns")
  void tablesExist() {
    assertThat(columnNames("rating_dimensions"))
        .contains("id", "code", "label", "sort_order", "created_by", "created_at");
    assertThat(columnNames("workorder_ratings"))
        .contains("id", "workorder_id", "rating_type", "rated_user_id", "rater_user_id", "created_at");
    assertThat(columnNames("workorder_rating_scores"))
        .contains("rating_id", "dimension_id", "score");
  }

  @Test
  @DisplayName("10.8-DB-002 P0 rating_dimensions seed rows are present (Speed/Work Quality/Tidiness)")
  void dimensionsSeeded() {
    var codes = jdbc.queryForList(
        "SELECT code FROM rating_dimensions ORDER BY sort_order", String.class);
    assertThat(codes).containsExactly("SPEED", "WORK_QUALITY", "TIDINESS");
  }

  @Test
  @DisplayName("10.8-DB-003 P0 rating_dimensions code is unique and rejects duplicates")
  void dimensionCodeUnique() {
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO rating_dimensions (id, code, label, sort_order, created_by, created_at)
        VALUES (?, 'SPEED', 'Duplicate', 9, ?, ?)
        """, UUID.randomUUID(), UUID.randomUUID(), TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.8-DB-004 P0 workorder_ratings rating_type CHECK accepts valid types and rejects unknown")
  void ratingTypeCheck() {
    var machineId = seedMachine();
    seedWorkorder("WO-2409-RTCK", machineId);
    var dimId = seedDimension("DIM_A", 4);
    for (var type : java.util.List.of("TECHNICIAN", "WORKORDER")) {
      var ratingId = UUID.randomUUID();
      jdbc.update("""
          INSERT INTO workorder_ratings (id, workorder_id, rating_type, rated_user_id, rater_user_id, created_at)
          VALUES (?, ?, ?, ?, ?, ?)
          """, ratingId, "WO-2409-RTCK", type, UUID.randomUUID(), UUID.randomUUID(), TS);
      jdbc.update("""
          INSERT INTO workorder_rating_scores (rating_id, dimension_id, score)
          VALUES (?, ?, 3)
          """, ratingId, dimId);
    }
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO workorder_ratings (id, workorder_id, rating_type, rated_user_id, rater_user_id, created_at)
        VALUES (?, ?, 'BOGUS', ?, ?, ?)
        """, UUID.randomUUID(), "WO-2409-RTCK", UUID.randomUUID(), UUID.randomUUID(), TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.8-DB-005 P0 workorder_ratings identity unique blocks a duplicate TECHNICIAN rating")
  void technicianIdentityUnique() {
    var machineId = seedMachine();
    seedWorkorder("WO-2409-IDT", machineId);
    var ratedUserId = UUID.randomUUID();
    var raterUserId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO workorder_ratings (id, workorder_id, rating_type, rated_user_id, rater_user_id, created_at)
        VALUES (?, ?, 'TECHNICIAN', ?, ?, ?)
        """, UUID.randomUUID(), "WO-2409-IDT", ratedUserId, raterUserId, TS);

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO workorder_ratings (id, workorder_id, rating_type, rated_user_id, rater_user_id, created_at)
        VALUES (?, ?, 'TECHNICIAN', ?, ?, ?)
        """, UUID.randomUUID(), "WO-2409-IDT", ratedUserId, raterUserId, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.8-DB-005b P0 WORKORDER ratings are unique per workorder (partial index)")
  void workorderTypeUniquePerWorkorder() {
    var machineId = seedMachine();
    seedWorkorder("WO-2409-IDW", machineId);
    jdbc.update("""
        INSERT INTO workorder_ratings (id, workorder_id, rating_type, rated_user_id, rater_user_id, created_at)
        VALUES (?, ?, 'WORKORDER', NULL, ?, ?)
        """, UUID.randomUUID(), "WO-2409-IDW", UUID.randomUUID(), TS);

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO workorder_ratings (id, workorder_id, rating_type, rated_user_id, rater_user_id, created_at)
        VALUES (?, ?, 'WORKORDER', NULL, ?, ?)
        """, UUID.randomUUID(), "WO-2409-IDW", UUID.randomUUID(), TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.8-DB-006 P0 workorder_rating_scores score CHECK rejects out-of-range values")
  void scoreCheck() {
    var machineId = seedMachine();
    seedWorkorder("WO-2409-SCK", machineId);
    var ratingId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO workorder_ratings (id, workorder_id, rating_type, rated_user_id, rater_user_id, created_at)
        VALUES (?, ?, 'WORKORDER', NULL, ?, ?)
        """, ratingId, "WO-2409-SCK", UUID.randomUUID(), TS);
    var dimA = seedDimension("DIM_B", 5);
    var dimB = seedDimension("DIM_B2", 6);
    jdbc.update("""
        INSERT INTO workorder_rating_scores (rating_id, dimension_id, score)
        VALUES (?, ?, 1)
        """, ratingId, dimA);
    jdbc.update("""
        INSERT INTO workorder_rating_scores (rating_id, dimension_id, score)
        VALUES (?, ?, 5)
        """, ratingId, dimB);
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO workorder_rating_scores (rating_id, dimension_id, score)
        VALUES (?, ?, 6)
        """, ratingId, dimA))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.8-DB-007 P1 workorder_rating_scores FK rejects an unknown dimension")
  void scoreDimensionFk() {
    var machineId = seedMachine();
    seedWorkorder("WO-2409-SFK", machineId);
    var ratingId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO workorder_ratings (id, workorder_id, rating_type, rated_user_id, rater_user_id, created_at)
        VALUES (?, ?, 'WORKORDER', NULL, ?, ?)
        """, ratingId, "WO-2409-SFK", UUID.randomUUID(), TS);
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO workorder_rating_scores (rating_id, dimension_id, score)
        VALUES (?, ?, 3)
        """, ratingId, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("10.8-DB-008 P1 workorder_rating_scores cascade when the rating is deleted")
  void scoresCascadeOnRatingDelete() {
    var machineId = seedMachine();
    seedWorkorder("WO-2409-CAS", machineId);
    var ratingId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO workorder_ratings (id, workorder_id, rating_type, rated_user_id, rater_user_id, created_at)
        VALUES (?, ?, 'WORKORDER', NULL, ?, ?)
        """, ratingId, "WO-2409-CAS", UUID.randomUUID(), TS);
    var dimId = seedDimension("DIM_C", 6);
    jdbc.update("""
        INSERT INTO workorder_rating_scores (rating_id, dimension_id, score)
        VALUES (?, ?, 4)
        """, ratingId, dimId);

    jdbc.update("DELETE FROM workorder_ratings WHERE id = ?", ratingId);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM workorder_rating_scores WHERE rating_id = ?", Long.class, ratingId)).isZero();
  }

  @Test
  @DisplayName("10.8-DB-009 P0 audit_log entity_type CHECK accepts WORKORDER_RATING and RATING_DIMENSION")
  void auditEntityTypeAcceptsRatingTypes() {
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "WORKORDER_RATING",
        UUID.randomUUID(), "WO-2409-00001", TS);
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), UUID.randomUUID(), "audit-actor", "CREATE", "RATING_DIMENSION",
        UUID.randomUUID(), "SPEED", TS);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type IN ('WORKORDER_RATING','RATING_DIMENSION')",
        Long.class)).isEqualTo(2L);
  }

  @Test
  @DisplayName("10.8-DB-010 P1 audit_log entity_type CHECK still rejects unknown types")
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

  private void seedWorkorder(String id, UUID machineId) {
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'CLOSED', ?, ?, ?)
        """, id, machineId, TS, TS);
  }

  private UUID seedDimension(String code, int sortOrder) {
    var id = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO rating_dimensions (id, code, label, sort_order, created_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """, id, code, "Dim " + code, sortOrder, UUID.randomUUID(), TS);
    return id;
  }
}