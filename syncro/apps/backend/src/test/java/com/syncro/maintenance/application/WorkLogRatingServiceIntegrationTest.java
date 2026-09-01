package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.maintenance.application.WorkLogRatingService.WorkLogRatingCriterionInUseException;
import com.syncro.maintenance.application.WorkLogRatingService.WorkLogRatingNotClosedException;
import com.syncro.maintenance.application.WorkLogRatingService.WorkLogRatingNotCompletedException;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Story 17-4 real-Postgres evidence (blueprint C1/C2, FR-121): a persisted
 * work_log_ratings row for a completed work log on a CLOSED workorder with the
 * WORK_LOG_RATING audit row (V5 CHECK), the not-closed and not-completed
 * rejections, the duplicate (work_log_id, criterion_id) rejection, and the
 * in-use criterion delete guard — all against real Postgres via JDBC.
 */
class WorkLogRatingServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant FIXED = Instant.parse("2026-09-01T00:00:00Z");

  @MockitoBean
  private Clock clock;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private WorkLogRatingService workLogRatings;

  @Autowired
  private EntityManager entityManager;

  @BeforeEach
  void setUpClock() {
    when(clock.instant()).thenReturn(FIXED);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
  }

  @Test
  @DisplayName("17.4-IT-001 P0 rate a completed work log on a CLOSED workorder persists rows, technician from the log, and audit WORK_LOG_RATING")
  void rateWorkLogPersistsAndAudits() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);
    var criterionId = UUID.randomUUID();
    insertCriterion(criterionId, "Speed", 1, 5);

    var views = workLogRatings.rateWorkLog(admin, seed.workOrderId, seed.workLogId,
        Map.of(criterionId, 4));
    entityManager.flush();

    // work_log_ratings row persisted with the derived technician's work log.
    var rowCount = jdbc.queryForObject(
        "SELECT count(*) FROM work_log_ratings WHERE work_log_id = ?::uuid AND criterion_id = ?::uuid",
        Integer.class, seed.workLogId.toString(), criterionId.toString());
    assertThat(rowCount).isEqualTo(1);
    var workLog = jdbc.queryForObject(
        "SELECT work_log_id FROM work_log_ratings WHERE criterion_id = ?::uuid",
        String.class, criterionId.toString());
    assertThat(UUID.fromString(workLog)).isEqualTo(seed.workLogId);
    var score = jdbc.queryForObject(
        "SELECT score FROM work_log_ratings WHERE criterion_id = ?::uuid",
        Integer.class, criterionId.toString());
    assertThat(score).isEqualTo(4);
    var ratedBy = jdbc.queryForObject(
        "SELECT rated_by FROM work_log_ratings WHERE criterion_id = ?::uuid",
        String.class, criterionId.toString());
    assertThat(UUID.fromString(ratedBy)).isEqualTo(seed.actorId);
    assertThat(views).hasSize(1);
    assertThat(views.getFirst().score()).isEqualTo(4);

    // WORK_LOG_RATING audit row written (V5 CHECK accepts it).
    var audit = jdbc.queryForObject("""
        SELECT count(*) FROM audit_log
        WHERE entity_label = ? AND entity_type = 'WORK_LOG_RATING' AND action = 'CREATE'
        """, Integer.class, seed.workOrderId);
    assertThat(audit).isEqualTo(1);
  }

  @Test
  @DisplayName("17.4-IT-002 P0 rating on a non-CLOSED workorder is rejected and no row is written")
  void notClosedRejectedNoRowWritten() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);
    var criterionId = UUID.randomUUID();
    insertCriterion(criterionId, "Speed", 1, 5);

    // Re-open the workorder (seed() leaves it CLOSED for the happy path).
    jdbc.update("UPDATE work_orders SET status = 'IN_PROGRESS' WHERE id = ?", seed.workOrderId);

    assertThatThrownBy(() -> workLogRatings.rateWorkLog(admin, seed.workOrderId, seed.workLogId,
        Map.of(criterionId, 3)))
        .isInstanceOf(WorkLogRatingNotClosedException.class);

    var count = jdbc.queryForObject(
        "SELECT count(*) FROM work_log_ratings WHERE work_log_id = ?::uuid",
        Integer.class, seed.workLogId.toString());
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("17.4-IT-003 P0 rating an incomplete work log is rejected (RATING_WORKLOG_NOT_COMPLETED)")
  void notCompletedRejected() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);
    var criterionId = UUID.randomUUID();
    insertCriterion(criterionId, "Speed", 1, 5);

    // Open a second, incomplete work log on the same workorder.
    var openLogId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO work_logs (id, work_assignment_id, work_order_id, technician_id, start_time,
                               activity_note, created_at, updated_at)
        VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?, 'open log', now(), now())
        """, openLogId.toString(), seed.assignmentId.toString(), seed.workOrderId,
        seed.technicianId.toString(), Timestamp.from(FIXED.minus(java.time.Duration.ofMinutes(30))));

    assertThatThrownBy(() -> workLogRatings.rateWorkLog(admin, seed.workOrderId, openLogId,
        Map.of(criterionId, 3)))
        .isInstanceOf(WorkLogRatingNotCompletedException.class);

    var count = jdbc.queryForObject(
        "SELECT count(*) FROM work_log_ratings WHERE work_log_id = ?::uuid",
        Integer.class, openLogId.toString());
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("17.4-IT-004 P0 duplicate (work_log_id, criterion_id) rating is rejected by the unique constraint")
  void duplicateRejected() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);
    var criterionId = UUID.randomUUID();
    insertCriterion(criterionId, "Speed", 1, 5);

    workLogRatings.rateWorkLog(admin, seed.workOrderId, seed.workLogId, Map.of(criterionId, 4));
    entityManager.flush();

    assertThatThrownBy(() -> workLogRatings.rateWorkLog(admin, seed.workOrderId, seed.workLogId,
        Map.of(criterionId, 5)))
        .isInstanceOf(WorkLogRatingService.WorkLogRatingAlreadyExistsException.class);

    var count = jdbc.queryForObject(
        "SELECT count(*) FROM work_log_ratings WHERE work_log_id = ?::uuid AND criterion_id = ?::uuid",
        Integer.class, seed.workLogId.toString(), criterionId.toString());
    assertThat(count).isEqualTo(1);
  }

  @Test
  @DisplayName("17.4-IT-005 P0 SUPER_ADMIN criterion CRUD persists and the in-use delete guard rejects")
  void criterionCrudAndInUseGuard() {
    // The admin must exist in auth_users: work_log_ratings.rated_by FKs to it.
    var adminId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, created_at, updated_at)
        VALUES (?::uuid, ?, 'x', 'SUPER_ADMIN', true, now(), now())
        """, adminId.toString(), "itwlr-admin-" + UUID.randomUUID() + "@syncro.dev");
    var admin = new AuthenticatedUser(adminId.toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var created = workLogRatings.createCriterion(admin,
        new WorkLogRatingService.CreateCriterionCommand("Speed", "Execution speed", 1, 5, null, true, 1));
    entityManager.flush();

    var row = jdbc.queryForObject(
        "SELECT count(*) FROM work_log_rating_criteria WHERE id = ?::uuid AND name = 'Speed'",
        Integer.class, created.id().toString());
    assertThat(row).isEqualTo(1);

    var updated = workLogRatings.updateCriterion(admin, created.id(),
        new WorkLogRatingService.UpdateCriterionCommand("Quickness", "Execution speed", 1, 5, null, true, 2));
    entityManager.flush();
    assertThat(updated.name()).isEqualTo("Quickness");
    var updatedRow = jdbc.queryForObject(
        "SELECT count(*) FROM work_log_rating_criteria WHERE id = ?::uuid AND name = 'Quickness' AND sort_order = 2",
        Integer.class, created.id().toString());
    assertThat(updatedRow).isEqualTo(1);

    // Delete with no ratings is allowed.
    workLogRatings.deleteCriterion(admin, created.id());
    entityManager.flush();
    var deleted = jdbc.queryForObject(
        "SELECT count(*) FROM work_log_rating_criteria WHERE id = ?::uuid",
        Integer.class, created.id().toString());
    assertThat(deleted).isZero();

    // In-use guard: rate against a criterion, then try to delete it.
    var seed = seed();
    var inUseCriterion = workLogRatings.createCriterion(admin,
        new WorkLogRatingService.CreateCriterionCommand("Tidiness", null, 1, 5, null, true, 3));
    entityManager.flush();
    workLogRatings.rateWorkLog(admin, seed.workOrderId, seed.workLogId, Map.of(inUseCriterion.id(), 3));
    entityManager.flush();

    assertThatThrownBy(() -> workLogRatings.deleteCriterion(admin, inUseCriterion.id()))
        .isInstanceOf(WorkLogRatingCriterionInUseException.class);
    var stillThere = jdbc.queryForObject(
        "SELECT count(*) FROM work_log_rating_criteria WHERE id = ?::uuid",
        Integer.class, inUseCriterion.id().toString());
    assertThat(stillThere).isEqualTo(1);
  }

  private void insertCriterion(UUID criterionId, String name, int minScore, int maxScore) {
    jdbc.update("""
        INSERT INTO work_log_rating_criteria (id, name, min_score, max_score, is_active, sort_order,
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
    var workOrderId = "WO-ITWLR-" + UUID.randomUUID().toString().substring(0, 8);

    jdbc.update("""
        INSERT INTO plants (id, code, name, created_at, updated_at)
        VALUES (?::uuid, 'ITWLR-PLANT', 'ITWLR Plant', now(), now())
        """, plantId.toString());
    jdbc.update("""
        INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at)
        VALUES (?::uuid, ?::uuid, 'ITWLR Group', now(), now())
        """, groupId.toString(), plantId.toString());
    jdbc.update("""
        INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at)
        VALUES (?::uuid, ?::uuid, ?::uuid, 'ITWLR-MC', 'ITWLR Machine', 'ACTIVE', now(), now())
        """, machineId.toString(), plantId.toString(), groupId.toString());
    jdbc.update("""
        INSERT INTO work_order_categories (id, code, label, created_at, updated_at)
        VALUES (?::uuid, '96', 'ITWLR Category', now(), now())
        """, categoryId.toString());
    jdbc.update("""
        INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, created_at, updated_at)
        VALUES (?::uuid, ?, 'x', 'TECHNICIAN', true, now(), now())
        """, technicianId.toString(), "itwlr-tech-" + UUID.randomUUID() + "@syncro.dev");
    jdbc.update("""
        INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, created_at, updated_at)
        VALUES (?::uuid, ?, 'x', 'SUPER_ADMIN', true, now(), now())
        """, actorId.toString(), "itwlr-actor-" + UUID.randomUUID() + "@syncro.dev");
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, category_id, machine_id, description, created_at, updated_at, sync_version)
        VALUES (?, 'INTERNAL', 'CLOSED', ?::uuid, ?::uuid, 'ITWLR work-log rating test', ?, ?, 1)
        """, workOrderId, categoryId.toString(), machineId.toString(),
        Timestamp.from(FIXED.minus(java.time.Duration.ofHours(2))), Timestamp.from(FIXED.minus(java.time.Duration.ofHours(2))));

    var assignmentId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO work_assignments (id, parent_type, work_order_id, technician_id, assigned_by, assigned_at,
                                      created_at, updated_at, is_active)
        VALUES (?::uuid, 'CORRECTIVE_WO', ?, ?::uuid, ?::uuid, ?, now(), now(), true)
        """, assignmentId.toString(), workOrderId, technicianId.toString(), actorId.toString(), Timestamp.from(FIXED.minus(java.time.Duration.ofHours(2))));

    var workLogId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO work_logs (id, work_assignment_id, work_order_id, technician_id, start_time, end_time,
                               stopped_reason, activity_note, created_at, updated_at)
        VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?, ?, 'COMPLETED', 'replaced bearing', now(), now())
        """, workLogId.toString(), assignmentId.toString(), workOrderId, technicianId.toString(),
        Timestamp.from(FIXED.minus(java.time.Duration.ofHours(2))), Timestamp.from(FIXED.minus(java.time.Duration.ofHours(1))));

    return new Seed(workOrderId, technicianId, actorId, assignmentId, workLogId);
  }

  private record Seed(String workOrderId, UUID technicianId, UUID actorId, UUID assignmentId, UUID workLogId) {
  }
}