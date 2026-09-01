package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.maintenance.application.WorkAssignmentService.AssignWorkAssignmentCommand;
import com.syncro.maintenance.application.WorkAssignmentService.AssignmentAlreadyDroppedException;
import com.syncro.maintenance.application.WorkAssignmentService.AssignmentAlreadyExistsException;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Story 17-1 real-Postgres evidence: first-assignment transition + history + audit,
 * the active-duplicate pre-check, the friendly unique-constraint mapping, the drop
 * conditional update, and proof that V3's CHECK extension preserved the audit_log
 * immutability trigger.
 */
class WorkAssignmentServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant FIXED = Instant.parse("2026-09-01T00:00:00Z");

  @MockitoBean
  private Clock clock;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private WorkAssignmentService workAssignments;

  @Autowired
  private EntityManager entityManager;

  @BeforeEach
  void setUpClock() {
    when(clock.instant()).thenReturn(FIXED);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
  }

  @Test
  @DisplayName("17.1-IT-001 P0 first assignment on OPEN transitions to IN_PROGRESS with one history row + audits")
  void firstAssignmentTransitionsAndAudits() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var view = workAssignments.assign(admin, seed.workOrderId, new AssignWorkAssignmentCommand(seed.technicianId));
    entityManager.flush();

    assertThat(view.isActive()).isTrue();
    assertThat(view.technicianId()).isEqualTo(seed.technicianId());

    var status = jdbc.queryForObject(
        "SELECT status FROM work_orders WHERE id = ?", String.class, seed.workOrderId);
    assertThat(status).isEqualTo(WorkOrderStatus.IN_PROGRESS.name());

    var history = jdbc.queryForList("""
        SELECT from_status, to_status, source, actor_type FROM work_order_status_history
        WHERE work_order_id = ?
        """, seed.workOrderId);
    assertThat(history).hasSize(1);
    assertThat(history.getFirst()).containsEntry("from_status", "OPEN")
        .containsEntry("to_status", "IN_PROGRESS")
        .containsEntry("source", "MANUAL");

    var auditTypes = jdbc.queryForList("""
        SELECT action, entity_type FROM audit_log
        WHERE entity_label = ? ORDER BY created_at
        """, seed.workOrderId);
    assertThat(auditTypes).hasSize(2);
    assertThat(auditTypes).anyMatch(row -> "CREATE".equals(row.get("action"))
        && "WORK_ASSIGNMENT".equals(row.get("entity_type")));
    assertThat(auditTypes).anyMatch(row -> "UPDATE".equals(row.get("action"))
        && "WORK_ORDER".equals(row.get("entity_type")));
  }

  @Test
  @DisplayName("17.1-IT-002 P0 re-assigning the same technician is rejected by the active-duplicate pre-check")
  void reAssigningSameTechnicianRejected() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);

    workAssignments.assign(admin, seed.workOrderId, new AssignWorkAssignmentCommand(seed.technicianId));
    entityManager.flush();

    assertThatThrownBy(() -> workAssignments.assign(admin, seed.workOrderId,
        new AssignWorkAssignmentCommand(seed.technicianId)))
        .isInstanceOf(AssignmentAlreadyExistsException.class);

    var activeCount = jdbc.queryForObject("""
        SELECT count(*) FROM work_assignments
        WHERE work_order_id = ? AND technician_id = ?::uuid AND is_active = true
        """, Integer.class, seed.workOrderId, seed.technicianId.toString());
    assertThat(activeCount).isEqualTo(1);

    var historyCount = jdbc.queryForObject(
        "SELECT count(*) FROM work_order_status_history WHERE work_order_id = ?",
        Integer.class, seed.workOrderId);
    assertThat(historyCount).isEqualTo(1);
  }

  @Test
  @DisplayName("17.1-IT-003 P0 a real unique-constraint conflict maps to AssignmentAlreadyExistsException, not a 500")
  void realUniqueConflictMapsToFriendlyException() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);

    // Seed an INACTIVE row with the SAME (work_order_id, technician_id, assigned_at=FIXED)
    // the service will use: the pre-check passes (row inactive), so the service insert
    // collides on uq_work_assignments_wo_tech_at.
    jdbc.update("""
        INSERT INTO work_assignments (id, parent_type, work_order_id, technician_id, assigned_by, assigned_at,
                                      created_at, updated_at, is_active)
        VALUES (?::uuid, 'CORRECTIVE_WO', ?, ?::uuid, ?::uuid, ?, now(), now(), false)
        """, UUID.randomUUID().toString(), seed.workOrderId, seed.technicianId.toString(),
        seed.actorId().toString(), Timestamp.from(FIXED));

    assertThatThrownBy(() -> workAssignments.assign(admin, seed.workOrderId,
        new AssignWorkAssignmentCommand(seed.technicianId)))
        .isInstanceOf(AssignmentAlreadyExistsException.class);
  }

  @Test
  @DisplayName("17.1-IT-004 P0 drop soft-deactivates against real Postgres and leaves the row in place")
  void dropSoftDeactivatesAgainstRealDb() {
    var seed = seed();
    var admin = new AuthenticatedUser(seed.actorId().toString(), "super@syncro.dev", ApplicationRole.SUPER_ADMIN);
    var assignmentId = UUID.randomUUID();

    jdbc.update("""
        INSERT INTO work_assignments (id, parent_type, work_order_id, technician_id, assigned_by, assigned_at,
                                      created_at, updated_at, is_active)
        VALUES (?::uuid, 'CORRECTIVE_WO', ?, ?::uuid, ?::uuid, ?, now(), now(), true)
        """, assignmentId.toString(), seed.workOrderId, seed.technicianId.toString(),
        seed.actorId().toString(), Timestamp.from(FIXED));

    var view = workAssignments.drop(admin, seed.workOrderId, assignmentId);

    assertThat(view.isActive()).isFalse();
    assertThat(view.droppedAt()).isEqualTo(FIXED);
    assertThat(view.droppedBy()).isEqualTo(UUID.fromString(admin.id()));

    var active = jdbc.queryForObject(
        "SELECT is_active FROM work_assignments WHERE id = ?::uuid", Boolean.class, assignmentId.toString());
    assertThat(active).isFalse();

    var rowCount = jdbc.queryForObject(
        "SELECT count(*) FROM work_assignments WHERE id = ?::uuid", Integer.class, assignmentId.toString());
    assertThat(rowCount).isEqualTo(1);

    assertThatThrownBy(() -> workAssignments.drop(admin, seed.workOrderId, assignmentId))
        .isInstanceOf(AssignmentAlreadyDroppedException.class);
  }

  @Test
  @DisplayName("17.1-IT-005 P0 audit_log accepts WORK_ASSIGNMENT and the immutability trigger still blocks UPDATE/DELETE")
  void auditLogAcceptsWorkAssignmentAndStaysImmutable() {
    var seed = seed();
    var auditId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO audit_log (id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
                               plant_id, previous_value, new_value, decision_id, created_at)
        VALUES (?::uuid, ?::uuid, 'it', 'CREATE', 'WORK_ASSIGNMENT', ?::uuid, ?,
                ?::uuid, NULL, '{}', NULL, ?)
        """, auditId.toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        seed.workOrderId, seed.plantId.toString(), Timestamp.from(FIXED));

    var entityType = jdbc.queryForObject(
        "SELECT entity_type FROM audit_log WHERE id = ?::uuid", String.class, auditId.toString());
    assertThat(entityType).isEqualTo("WORK_ASSIGNMENT");

    assertThatThrownBy(() -> jdbc.update(
        "UPDATE audit_log SET new_value = '{}' WHERE id = ?::uuid", auditId.toString()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE id = ?::uuid", auditId.toString()))
        .isInstanceOf(DataAccessException.class);
  }

  private Seed seed() {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var categoryId = UUID.randomUUID();
    var technicianId = UUID.randomUUID();
    var actorId = UUID.randomUUID();
    var workOrderId = "WO-IT-" + UUID.randomUUID().toString().substring(0, 8);

    jdbc.update("""
        INSERT INTO plants (id, code, name, created_at, updated_at)
        VALUES (?::uuid, 'IT-PLANT', 'IT Plant', now(), now())
        """, plantId.toString());
    jdbc.update("""
        INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at)
        VALUES (?::uuid, ?::uuid, 'IT Group', now(), now())
        """, groupId.toString(), plantId.toString());
    jdbc.update("""
        INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at)
        VALUES (?::uuid, ?::uuid, ?::uuid, 'IT-MC', 'IT Machine', 'ACTIVE', now(), now())
        """, machineId.toString(), plantId.toString(), groupId.toString());
    jdbc.update("""
        INSERT INTO work_order_categories (id, code, label, created_at, updated_at)
        VALUES (?::uuid, '98', 'IT Category', now(), now())
        """, categoryId.toString());
    jdbc.update("""
        INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, created_at, updated_at)
        VALUES (?::uuid, ?, 'x', 'TECHNICIAN', true, now(), now())
        """, technicianId.toString(), "it-tech-" + UUID.randomUUID() + "@syncro.dev");
    jdbc.update("""
        INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, created_at, updated_at)
        VALUES (?::uuid, ?, 'x', 'SUPER_ADMIN', true, now(), now())
        """, actorId.toString(), "it-actor-" + UUID.randomUUID() + "@syncro.dev");
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, category_id, machine_id, description, created_at, updated_at, sync_version)
        VALUES (?, 'INTERNAL', 'OPEN', ?::uuid, ?::uuid, 'IT assignment test', now(), now(), 1)
        """, workOrderId, categoryId.toString(), machineId.toString());

    return new Seed(workOrderId, machineId, plantId, technicianId, actorId);
  }

  private record Seed(String workOrderId, UUID machineId, UUID plantId, UUID technicianId, UUID actorId) {
  }
}