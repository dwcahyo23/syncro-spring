package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.maintenance.application.WorkLogService.CreateWorkLogCommand;
import com.syncro.maintenance.application.WorkLogService.WorkLogAssignmentInactiveException;
import com.syncro.maintenance.application.WorkLogService.WorkLogBackdateBeforeWorkorderException;
import com.syncro.maintenance.domain.workorder.WorkLogStoppedReason;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Story 17-2 real-Postgres evidence (blueprint B4, AD-18): a persisted work_logs row with
 * technician_id derived from the active assignment, the WORK_LOG audit row (V4 CHECK),
 * the MTTR recompute (repair sessions + work logs) persisted on work_orders, the backdate
 * rejection, the completion gate satisfied by a completed work log, and the non-active
 * assignment rejection — all against real Postgres via JDBC.
 */
class WorkLogServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant FIXED = Instant.parse("2026-09-01T00:00:00Z");

  @MockitoBean
  private Clock clock;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private WorkLogService workLogs;

  @Autowired
  private WorkOrderService workOrders;

  @Autowired
  private EntityManager entityManager;

  @BeforeEach
  void setUpClock() {
    when(clock.instant()).thenReturn(FIXED);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
  }

  @Test
  @DisplayName("17.2-IT-001 P0 create on active assignment persists the row, technician_id from the assignment, audit WORK_LOG, and recomputes MTTR")
  void createPersistsLogAuditsAndRecomputesMttr() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);

    // Pre-seed a completed repair session (30 min) so MTTR = sessions + work log durations.
    jdbc.update("""
        INSERT INTO repair_sessions (id, work_order_id, technician_id, description, started_at, ended_at,
                                     duration_minutes, created_at, updated_at)
        VALUES (?::uuid, ?, ?::uuid, 'legacy', ?, ?, 30, now(), now())
        """, UUID.randomUUID().toString(), seed.workOrderId, seed.technicianId.toString(),
        Timestamp.from(FIXED.minus(java.time.Duration.ofMinutes(60))),
        Timestamp.from(FIXED.minus(java.time.Duration.ofMinutes(30))));

    var start = FIXED.minus(java.time.Duration.ofMinutes(45));
    var end = FIXED.minus(java.time.Duration.ofMinutes(15));
    var view = workLogs.create(admin, seed.workOrderId, new CreateWorkLogCommand(
        seed.assignmentId, start, end, WorkLogStoppedReason.COMPLETED, "Replaced bearing", "Done", null));
    entityManager.flush();

    // work_logs row persisted with technician_id from the assignment.
    var technician = jdbc.queryForObject(
        "SELECT technician_id FROM work_logs WHERE id = ?::uuid", String.class, view.id().toString());
    assertThat(UUID.fromString(technician)).isEqualTo(seed.technicianId());
    var workAssignment = jdbc.queryForObject(
        "SELECT work_assignment_id FROM work_logs WHERE id = ?::uuid", String.class, view.id().toString());
    assertThat(UUID.fromString(workAssignment)).isEqualTo(seed.assignmentId);

    // MTTR = 30 (session) + 30 (log duration) = 60, persisted on work_orders.
    var mttr = jdbc.queryForObject(
        "SELECT mttr_minutes FROM work_orders WHERE id = ?", Long.class, seed.workOrderId);
    assertThat(mttr).isEqualTo(60L);

    // WORK_LOG audit row written (V4 CHECK accepts it).
    var audit = jdbc.queryForObject("""
        SELECT count(*) FROM audit_log
        WHERE entity_label = ? AND entity_type = 'WORK_LOG' AND action = 'CREATE'
        """, Integer.class, seed.workOrderId);
    assertThat(audit).isEqualTo(1);
  }

  @Test
  @DisplayName("17.2-IT-002 P0 backdate before workorder created_at is rejected and no row is written")
  void backdateRejectedNoRowWritten() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var beforeCreatedAt = FIXED.minus(java.time.Duration.ofHours(2));
    assertThatThrownBy(() -> workLogs.create(admin, seed.workOrderId, new CreateWorkLogCommand(
        seed.assignmentId, beforeCreatedAt, FIXED, WorkLogStoppedReason.COMPLETED, "note", null, null)))
        .isInstanceOf(WorkLogBackdateBeforeWorkorderException.class);

    var count = jdbc.queryForObject(
        "SELECT count(*) FROM work_logs WHERE work_order_id = ?", Integer.class, seed.workOrderId);
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("17.2-IT-003 P0 a completed work log satisfies the completion gate (no repair session, no reason)")
  void completedWorkLogSatisfiesDoneGate() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);

    // Workorder is already IN_PROGRESS; create a completed work log.
    var start = FIXED.minus(java.time.Duration.ofMinutes(20));
    workLogs.create(admin, seed.workOrderId, new CreateWorkLogCommand(
        seed.assignmentId, start, FIXED, WorkLogStoppedReason.COMPLETED, "Fixed", "ok", null));
    entityManager.flush();

    // Transition to PENDING_REVIEW without a reason: allowed because a completed work log exists.
    var result = workOrders.transition(admin, seed.workOrderId,
        new com.syncro.maintenance.application.WorkOrderService.TransitionWorkOrderCommand(
            WorkOrderStatus.PENDING_REVIEW, null, null));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.PENDING_REVIEW);
    var status = jdbc.queryForObject(
        "SELECT status FROM work_orders WHERE id = ?", String.class, seed.workOrderId);
    assertThat(status).isEqualTo("PENDING_REVIEW");
  }

  @Test
  @DisplayName("17.2-IT-004 P0 create against an inactive assignment is rejected (WORKLOG_ASSIGNMENT_INACTIVE)")
  void inactiveAssignmentRejected() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);

    // Drop the assignment so it is inactive.
    jdbc.update("""
        UPDATE work_assignments SET is_active = false, dropped_at = now(), dropped_by = ?::uuid
        WHERE id = ?::uuid
        """, seed.actorId.toString(), seed.assignmentId.toString());

    assertThatThrownBy(() -> workLogs.create(admin, seed.workOrderId, new CreateWorkLogCommand(
        seed.assignmentId, FIXED, null, null, "note", null, null)))
        .isInstanceOf(WorkLogAssignmentInactiveException.class);

    var count = jdbc.queryForObject(
        "SELECT count(*) FROM work_logs WHERE work_order_id = ?", Integer.class, seed.workOrderId);
    assertThat(count).isZero();
  }

  private Seed seed() {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var categoryId = UUID.randomUUID();
    var technicianId = UUID.randomUUID();
    var actorId = UUID.randomUUID();
    var workOrderId = "WO-ITWL-" + UUID.randomUUID().toString().substring(0, 8);

    jdbc.update("""
        INSERT INTO plants (id, code, name, created_at, updated_at)
        VALUES (?::uuid, 'ITWL-PLANT', 'ITWL Plant', now(), now())
        """, plantId.toString());
    jdbc.update("""
        INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at)
        VALUES (?::uuid, ?::uuid, 'ITWL Group', now(), now())
        """, groupId.toString(), plantId.toString());
    jdbc.update("""
        INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at)
        VALUES (?::uuid, ?::uuid, ?::uuid, 'ITWL-MC', 'ITWL Machine', 'ACTIVE', now(), now())
        """, machineId.toString(), plantId.toString(), groupId.toString());
    jdbc.update("""
        INSERT INTO work_order_categories (id, code, label, created_at, updated_at)
        VALUES (?::uuid, '97', 'ITWL Category', now(), now())
        """, categoryId.toString());
    jdbc.update("""
        INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, created_at, updated_at)
        VALUES (?::uuid, ?, 'x', 'TECHNICIAN', true, now(), now())
        """, technicianId.toString(), "itwl-tech-" + UUID.randomUUID() + "@syncro.dev");
    jdbc.update("""
        INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, created_at, updated_at)
        VALUES (?::uuid, ?, 'x', 'SUPER_ADMIN', true, now(), now())
        """, actorId.toString(), "itwl-actor-" + UUID.randomUUID() + "@syncro.dev");
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, category_id, machine_id, description, created_at, updated_at, sync_version)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?::uuid, ?::uuid, 'ITWL work-log test', ?, ?, 1)
        """, workOrderId, categoryId.toString(), machineId.toString(),
        Timestamp.from(FIXED.minus(java.time.Duration.ofHours(1))), Timestamp.from(FIXED.minus(java.time.Duration.ofHours(1))));

    var assignmentId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO work_assignments (id, parent_type, work_order_id, technician_id, assigned_by, assigned_at,
                                      created_at, updated_at, is_active)
        VALUES (?::uuid, 'CORRECTIVE_WO', ?, ?::uuid, ?::uuid, ?, now(), now(), true)
        """, assignmentId.toString(), workOrderId, technicianId.toString(), actorId.toString(), Timestamp.from(FIXED));

    return new Seed(workOrderId, machineId, plantId, technicianId, actorId, assignmentId);
  }

  private record Seed(String workOrderId, UUID machineId, UUID plantId, UUID technicianId, UUID actorId,
      UUID assignmentId) {
  }
}