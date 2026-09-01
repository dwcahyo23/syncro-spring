package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.maintenance.application.WorkOrderQualityRatingService.QualityRatingAlreadyExistsException;
import com.syncro.maintenance.application.WorkOrderQualityRatingService.QualityRatingNotClosedException;
import com.syncro.maintenance.application.WorkOrderQualityRatingService.SubmitQualityRatingCommand;
import com.syncro.maintenance.domain.workorder.WorkRatingStatus;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Story 17-5 real-Postgres evidence (blueprint C4-C6, FR-124): a persisted
 * work_order_quality_ratings row with scores and technicians for a CLOSED
 * workorder, the WORK_ORDER_QUALITY_RATING audit row (V6 CHECK), the not-closed
 * rejection, the duplicate rejection, and the PENDING-to-EXPIRED transition on
 * read — all against real Postgres via JDBC.
 */
class WorkOrderQualityRatingServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant FIXED = Instant.parse("2026-09-01T00:00:00Z");

  @MockitoBean
  private Clock clock;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private WorkOrderQualityRatingService qualityRatings;

  @Autowired
  private EntityManager entityManager;

  @BeforeEach
  void setUpClock() {
    when(clock.instant()).thenReturn(FIXED);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
  }

  @Test
  @DisplayName("17.5-IT-001 P0 submit a quality rating on a CLOSED workorder persists rows, scores, technicians, and audit WORK_ORDER_QUALITY_RATING")
  void submitPersistsAndAudits() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);
    var criterionId = UUID.randomUUID();
    insertCriterion(criterionId, "Quality", 1, 5);

    var view = qualityRatings.submit(admin, seed.workOrderId,
        new SubmitQualityRatingCommand(List.of(seed.technicianId), Map.of(criterionId, 4),
            5, 4, 3));
    entityManager.flush();

    // work_order_quality_ratings row persisted.
    var ratingRow = jdbc.queryForMap(
        "SELECT * FROM work_order_quality_ratings WHERE work_order_id = ?", seed.workOrderId);
    assertThat(ratingRow.get("work_order_id")).isEqualTo(seed.workOrderId);
    assertThat(ratingRow.get("status")).isEqualTo("SUBMITTED");
    assertThat(ratingRow.get("cleanliness_score")).isEqualTo(5);
    assertThat(ratingRow.get("tidiness_score")).isEqualTo(4);
    assertThat(ratingRow.get("speed_score")).isEqualTo(3);
    assertThat(ratingRow.get("submitted_by")).isEqualTo(UUID.fromString(seed.actorId().toString()));

    var ratingId = (UUID) ratingRow.get("id");

    // work_order_quality_rating_scores row persisted.
    var scoreCount = jdbc.queryForObject(
        "SELECT count(*) FROM work_order_quality_rating_scores WHERE quality_rating_id = ?::uuid AND criterion_id = ?::uuid AND score = 4",
        Integer.class, ratingId.toString(), criterionId.toString());
    assertThat(scoreCount).isEqualTo(1);

    // work_order_quality_rating_technicians row persisted.
    var techCount = jdbc.queryForObject(
        "SELECT count(*) FROM work_order_quality_rating_technicians WHERE quality_rating_id = ?::uuid AND technician_id = ?::uuid",
        Integer.class, ratingId.toString(), seed.technicianId().toString());
    assertThat(techCount).isEqualTo(1);

    // Returned view matches.
    assertThat(view.status()).isEqualTo(WorkRatingStatus.SUBMITTED);
    assertThat(view.cleanlinessScore()).isEqualTo(5);
    assertThat(view.scores()).hasSize(1);
    assertThat(view.technicianIds()).containsExactly(seed.technicianId());

    // WORK_ORDER_QUALITY_RATING audit row written (V6 CHECK accepts it).
    var audit = jdbc.queryForObject("""
        SELECT count(*) FROM audit_log
        WHERE entity_label = ? AND entity_type = 'WORK_ORDER_QUALITY_RATING' AND action = 'CREATE'
        """, Integer.class, seed.workOrderId);
    assertThat(audit).isEqualTo(1);
  }

  @Test
  @DisplayName("17.5-IT-002 P0 submit on a non-CLOSED workorder is rejected and no row is written")
  void notClosedRejectedNoRowWritten() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);
    var criterionId = UUID.randomUUID();
    insertCriterion(criterionId, "Quality", 1, 5);

    // Re-open the workorder (seed() leaves it CLOSED for the happy path).
    jdbc.update("UPDATE work_orders SET status = 'IN_PROGRESS' WHERE id = ?", seed.workOrderId);

    assertThatThrownBy(() -> qualityRatings.submit(admin, seed.workOrderId,
        new SubmitQualityRatingCommand(List.of(seed.technicianId), Map.of(criterionId, 3),
            5, 4, 3)))
        .isInstanceOf(QualityRatingNotClosedException.class);

    var count = jdbc.queryForObject(
        "SELECT count(*) FROM work_order_quality_ratings WHERE work_order_id = ?",
        Integer.class, seed.workOrderId);
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("17.5-IT-003 P0 duplicate quality rating is rejected by the unique constraint")
  void duplicateRejected() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);
    var criterionId = UUID.randomUUID();
    insertCriterion(criterionId, "Quality", 1, 5);

    qualityRatings.submit(admin, seed.workOrderId,
        new SubmitQualityRatingCommand(List.of(seed.technicianId), Map.of(criterionId, 4),
            5, 4, 3));
    entityManager.flush();

    assertThatThrownBy(() -> qualityRatings.submit(admin, seed.workOrderId,
        new SubmitQualityRatingCommand(List.of(seed.technicianId), Map.of(criterionId, 5),
            5, 4, 3)))
        .isInstanceOf(QualityRatingAlreadyExistsException.class);

    var count = jdbc.queryForObject(
        "SELECT count(*) FROM work_order_quality_ratings WHERE work_order_id = ?",
        Integer.class, seed.workOrderId);
    assertThat(count).isEqualTo(1);
  }

  @Test
  @DisplayName("17.5-IT-004 P0 get transitions a past-due PENDING rating to EXPIRED on read")
  void expiredOnRead() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);
    var criterionId = UUID.randomUUID();
    insertCriterion(criterionId, "Quality", 1, 5);

    // Insert a PENDING rating with due_at in the past.
    var ratingId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO work_order_quality_ratings (id, work_order_id, status, due_at, created_at, updated_at)
        VALUES (?::uuid, ?, 'PENDING', ?, now(), now())
        """, ratingId.toString(), seed.workOrderId, Timestamp.from(FIXED.minus(java.time.Duration.ofHours(1))));

    // The clock is still FIXED (= now is after due_at), so get() should expire it.
    var view = qualityRatings.get(seed.workOrderId);

    assertThat(view.status()).isEqualTo(WorkRatingStatus.EXPIRED);
    var persistedStatus = jdbc.queryForObject(
        "SELECT status FROM work_order_quality_ratings WHERE id = ?::uuid",
        String.class, ratingId.toString());
    assertThat(persistedStatus).isEqualTo("EXPIRED");
  }

  private void insertCriterion(UUID criterionId, String name, int minScore, int maxScore) {
    jdbc.update("""
        INSERT INTO work_order_rating_criteria (id, name, min_score, max_score, is_active, sort_order,
                                                created_at, updated_at)
        VALUES (?::uuid, ?, ?, ?, true, 1, now(), now())
        """, criterionId.toString(), name, minScore, maxScore);
  }

  private Seed seed() {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var categoryId = UUID.randomUUID();
    var technicianId = UUID.randomUUID();
    var actorId = UUID.randomUUID();
    var workOrderId = "WO-ITQ-" + UUID.randomUUID().toString().substring(0, 8);

    jdbc.update("""
        INSERT INTO plants (id, code, name, created_at, updated_at)
        VALUES (?::uuid, 'ITQ-PLANT', 'ITQ Plant', now(), now())
        """, plantId.toString());
    jdbc.update("""
        INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at)
        VALUES (?::uuid, ?::uuid, 'ITQ Group', now(), now())
        """, groupId.toString(), plantId.toString());
    jdbc.update("""
        INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at)
        VALUES (?::uuid, ?::uuid, ?::uuid, 'ITQ-MC', 'ITQ Machine', 'ACTIVE', now(), now())
        """, machineId.toString(), plantId.toString(), groupId.toString());
    jdbc.update("""
        INSERT INTO work_order_categories (id, code, label, created_at, updated_at)
        VALUES (?::uuid, '97', 'ITQ Category', now(), now())
        """, categoryId.toString());
    jdbc.update("""
        INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, created_at, updated_at)
        VALUES (?::uuid, ?, 'x', 'TECHNICIAN', true, now(), now())
        """, technicianId.toString(), "itq-tech-" + UUID.randomUUID() + "@syncro.dev");
    jdbc.update("""
        INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, created_at, updated_at)
        VALUES (?::uuid, ?, 'x', 'SUPER_ADMIN', true, now(), now())
        """, actorId.toString(), "itq-actor-" + UUID.randomUUID() + "@syncro.dev");
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, category_id, machine_id, description, assigned_technician_id,
                                 created_at, updated_at, sync_version)
        VALUES (?, 'INTERNAL', 'CLOSED', ?::uuid, ?::uuid, 'ITQ quality rating test', ?::uuid, ?, ?, 1)
        """, workOrderId, categoryId.toString(), machineId.toString(), technicianId.toString(),
        Timestamp.from(FIXED.minus(java.time.Duration.ofHours(2))), Timestamp.from(FIXED.minus(java.time.Duration.ofHours(2))));

    return new Seed(workOrderId, technicianId, actorId);
  }

  private record Seed(String workOrderId, UUID technicianId, UUID actorId) {
  }
}