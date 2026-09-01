package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.application.WorkAssignmentService.AssignWorkAssignmentCommand;
import com.syncro.maintenance.application.WorkAssignmentService.AssignmentAlreadyDroppedException;
import com.syncro.maintenance.application.WorkAssignmentService.AssignmentAlreadyExistsException;
import com.syncro.maintenance.application.WorkAssignmentService.WorkAssignmentNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.InvalidStateTransitionException;
import com.syncro.maintenance.application.WorkOrderService.SelfAssignmentForbiddenException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderUserNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderForbiddenException;
import com.syncro.maintenance.domain.workorder.WorkAssignmentParentType;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkAssignmentEntity;
import com.syncro.maintenance.infrastructure.db.WorkAssignmentRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class WorkAssignmentServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

  @Mock
  private WorkAssignmentRepository assignments;
  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private WorkOrderStatusHistoryRepository statusHistory;
  @Mock
  private MachineRepository machines;
  @Mock
  private AuthUserRepository users;
  @Mock
  private OperationalScopeService scopes;
  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID technicianId = UUID.randomUUID();
  private final UUID secondTechnicianId = UUID.randomUUID();

  private WorkAssignmentService service;
  private MachineEntity machine;

  @BeforeEach
  void setUp() {
    service = new WorkAssignmentService(assignments, workOrders, statusHistory, machines, users, scopes,
        auditLog, clock);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
  }

  @Test
  @DisplayName("17.1-SVC-001 P0 first assignment on OPEN workorder transitions to IN_PROGRESS with history + audit")
  void firstAssignmentTransitionsToInProgress() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var entity = openEntity();
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(users.findById(technicianId)).thenReturn(Optional.of(technician(technicianId)));
    when(assignments.existsByWorkOrderIdAndTechnicianIdAndActiveTrue("WO-240900001", technicianId)).thenReturn(false);
    when(assignments.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.assign(user, "WO-240900001", new AssignWorkAssignmentCommand(technicianId));

    assertThat(result.isActive()).isTrue();
    assertThat(result.technicianId()).isEqualTo(technicianId);
    assertThat(result.assignedAt()).isEqualTo(NOW);
    assertThat(entity.getStatus()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
    verify(statusHistory).saveAndFlush(argThat(h -> "OPEN".equals(h.getFromStatus())
        && "IN_PROGRESS".equals(h.getToStatus())));
    // one assignment CREATE audit + one transition UPDATE audit
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.CREATE
        && r.entityType() == AuditEntityType.WORK_ASSIGNMENT
        && r.entityLabel().equals("WO-240900001")));
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.entityType() == AuditEntityType.WORK_ORDER
        && r.entityLabel().equals("WO-240900001")));
  }

  @Test
  @DisplayName("17.1-SVC-002 P0 additional assignment on an IN_PROGRESS workorder does not transition")
  void additionalAssignmentNoTransition() {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var entity = new WorkOrderEntity("WO-240900001", "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, UUID.randomUUID(),
        machineId, "desc", 0, null, technicianId, UUID.randomUUID(), NOW, NOW);
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(users.findById(secondTechnicianId)).thenReturn(Optional.of(technician(secondTechnicianId)));
    when(assignments.existsByWorkOrderIdAndTechnicianIdAndActiveTrue("WO-240900001", secondTechnicianId)).thenReturn(false);
    when(assignments.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.assign(user, "WO-240900001", new AssignWorkAssignmentCommand(secondTechnicianId));

    assertThat(result.technicianId()).isEqualTo(secondTechnicianId);
    assertThat(entity.getStatus()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
    verify(statusHistory, never()).saveAndFlush(any());
    verify(workOrders, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("17.1-SVC-003 P0 drop sets is_active=false with dropped_at/dropped_by and audits UPDATE")
  void dropSoftDeactivates() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var entity = openEntity();
    var assignmentId = UUID.randomUUID();
    var assignment = new WorkAssignmentEntity(assignmentId, WorkAssignmentParentType.CORRECTIVE_WO,
        "WO-240900001", technicianId, UUID.randomUUID(), NOW, null, null, true, NOW, NOW);
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(assignments.findById(assignmentId)).thenReturn(Optional.of(assignment));
    // The @Modifying deactivateIfActive both updates the row and (in a mock) mutates the
    // in-memory entity so the reloaded view reflects the drop, mirroring the DB behavior.
    when(assignments.deactivateIfActive(assignmentId, UUID.fromString(user.id()), NOW)).thenAnswer(invocation -> {
      assignment.drop(UUID.fromString(user.id()), NOW, NOW);
      return 1;
    });

    var result = service.drop(user, "WO-240900001", assignmentId);

    assertThat(result.isActive()).isFalse();
    assertThat(result.droppedAt()).isEqualTo(NOW);
    assertThat(result.droppedBy()).isEqualTo(UUID.fromString(user.id()));
    // lead technician unchanged
    assertThat(entity.getAssignedTechnicianId()).isNull();
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.entityType() == AuditEntityType.WORK_ASSIGNMENT
        && r.entityLabel().equals("WO-240900001")));
  }

  @Test
  @DisplayName("17.1-SVC-004 P0 duplicate assign maps the unique constraint to AssignmentAlreadyExistsException")
  void duplicateAssign() {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var entity = new WorkOrderEntity("WO-240900001", "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, UUID.randomUUID(),
        machineId, "desc", 0, null, technicianId, UUID.randomUUID(), NOW, NOW);
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(users.findById(technicianId)).thenReturn(Optional.of(technician(technicianId)));
    when(assignments.existsByWorkOrderIdAndTechnicianIdAndActiveTrue("WO-240900001", technicianId)).thenReturn(false);
    org.mockito.Mockito.doThrow(new DataIntegrityViolationException("dup",
        new org.hibernate.exception.ConstraintViolationException("dup",
            new java.sql.SQLException("dup"), "uq_work_assignments_wo_tech_at")))
        .when(assignments).saveAndFlush(any());

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkAssignmentCommand(technicianId)))
        .isInstanceOf(AssignmentAlreadyExistsException.class);
  }

  @Test
  @DisplayName("17.1-SVC-005 P0 SECTION_LEADER self-assignment throws SelfAssignmentForbiddenException")
  void selfAssignmentForbidden() {
    var selfId = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");
    var user = new AuthenticatedUser(selfId.toString(), "leader@syncro.dev", ApplicationRole.SECTION_LEADER);
    var entity = openEntity();
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(users.findById(selfId)).thenReturn(Optional.of(technician(selfId)));

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkAssignmentCommand(selfId)))
        .isInstanceOf(SelfAssignmentForbiddenException.class);
    verify(assignments, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("17.1-SVC-006 P0 non-technician assignee throws WorkorderForbiddenException")
  void nonTechnicianAssigneeForbidden() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var auditorId = UUID.randomUUID();
    var entity = openEntity();
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(users.findById(auditorId)).thenReturn(Optional.of(
        new AuthUserEntity(auditorId, "auditor@syncro.dev", "x", ApplicationRole.AUDITOR, true, NOW, NOW)));

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkAssignmentCommand(auditorId)))
        .isInstanceOf(WorkorderForbiddenException.class);
    verify(assignments, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("17.1-SVC-007 P0 EXTERNAL workorder is forbidden")
  void externalWorkorderForbidden() {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var entity = new WorkOrderEntity("EXT-0001", "EXTERNAL", null, WorkOrderStatus.OPEN, UUID.randomUUID(),
        machineId, "desc", 1, null, null, UUID.randomUUID(), NOW, NOW);
    when(workOrders.findByIdForUpdate("EXT-0001")).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.assign(user, "EXT-0001", new AssignWorkAssignmentCommand(technicianId)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("17.1-SVC-008 P0 assign to CLOSED workorder throws InvalidStateTransitionException")
  void closedWorkorderRejected() {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var entity = new WorkOrderEntity("WO-240900001", "INTERNAL", null, WorkOrderStatus.CLOSED, UUID.randomUUID(),
        machineId, "desc", 0, null, technicianId, UUID.randomUUID(), NOW, NOW);
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkAssignmentCommand(technicianId)))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  @DisplayName("17.1-SVC-009 P0 STAFF_MAINTENANCE cannot assign (leadership gate)")
  void staffCannotAssign() {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkAssignmentCommand(technicianId)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("17.1-SVC-010 P0 TECHNICIAN cannot assign (leadership gate)")
  void technicianCannotAssign() {
    var user = user(ApplicationRole.TECHNICIAN);

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkAssignmentCommand(technicianId)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("17.1-SVC-011 P0 unknown workorder throws WorkOrderNotFoundException")
  void unknownWorkorder() {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(workOrders.findByIdForUpdate("WO-2409NADA")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.assign(user, "WO-2409NADA", new AssignWorkAssignmentCommand(technicianId)))
        .isInstanceOf(WorkOrderNotFoundException.class);
  }

  @Test
  @DisplayName("17.1-SVC-012 P0 unknown assignee throws WorkOrderUserNotFoundException")
  void unknownAssignee() {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var entity = openEntity();
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(users.findById(technicianId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkAssignmentCommand(technicianId)))
        .isInstanceOf(WorkOrderUserNotFoundException.class);
  }

  @Test
  @DisplayName("17.1-SVC-013 P0 SECTION_LEADER out of scope is forbidden")
  void sectionLeaderOutOfScope() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var otherGroupId = UUID.randomUUID();
    var entity = openEntity();
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(otherGroupId), Set.of()));

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkAssignmentCommand(technicianId)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("17.1-SVC-014 P0 drop of an unknown assignment throws WorkAssignmentNotFoundException")
  void dropUnknownAssignment() {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var assignmentId = UUID.randomUUID();
    var entity = openEntity();
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(assignments.findById(assignmentId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.drop(user, "WO-240900001", assignmentId))
        .isInstanceOf(WorkAssignmentNotFoundException.class);
  }

  @Test
  @DisplayName("17.1-SVC-015 P0 dropping an already-dropped assignment throws AssignmentAlreadyDroppedException")
  void dropAlreadyDropped() {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var assignmentId = UUID.randomUUID();
    var entity = openEntity();
    var assignment = new WorkAssignmentEntity(assignmentId, WorkAssignmentParentType.CORRECTIVE_WO,
        "WO-240900001", technicianId, UUID.randomUUID(), NOW, NOW, UUID.randomUUID(), false, NOW, NOW);
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(assignments.findById(assignmentId)).thenReturn(Optional.of(assignment));
    // Already inactive: the atomic conditional update touches 0 rows.
    when(assignments.deactivateIfActive(assignmentId, UUID.fromString(user.id()), NOW)).thenReturn(0);

    assertThatThrownBy(() -> service.drop(user, "WO-240900001", assignmentId))
        .isInstanceOf(AssignmentAlreadyDroppedException.class);
    verify(assignments, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("17.1-SVC-016 P1 drop of an assignment on a different workorder throws WorkAssignmentNotFoundException")
  void dropWrongWorkorder() {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var assignmentId = UUID.randomUUID();
    var entity = openEntity();
    var assignment = new WorkAssignmentEntity(assignmentId, WorkAssignmentParentType.CORRECTIVE_WO,
        "WO-2409OTHER", technicianId, UUID.randomUUID(), NOW, null, null, true, NOW, NOW);
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(assignments.findById(assignmentId)).thenReturn(Optional.of(assignment));

    assertThatThrownBy(() -> service.drop(user, "WO-240900001", assignmentId))
        .isInstanceOf(WorkAssignmentNotFoundException.class);
  }

  @Test
  @DisplayName("17.1-SVC-017 P1 list returns assignments ordered by assignedAt asc")
  void listReturnsOrdered() {
    var first = new WorkAssignmentEntity(UUID.randomUUID(), WorkAssignmentParentType.CORRECTIVE_WO,
        "WO-240900001", technicianId, UUID.randomUUID(), NOW, null, null, true, NOW, NOW);
    var second = new WorkAssignmentEntity(UUID.randomUUID(), WorkAssignmentParentType.CORRECTIVE_WO,
        "WO-240900001", secondTechnicianId, UUID.randomUUID(), NOW.plusSeconds(60), null, null, true, NOW, NOW);
    when(workOrders.existsById("WO-240900001")).thenReturn(true);
    when(assignments.findByWorkOrderIdOrderByAssignedAtAsc("WO-240900001")).thenReturn(List.of(first, second));

    var result = service.list("WO-240900001");

    assertThat(result).hasSize(2);
    assertThat(result.get(0).technicianId()).isEqualTo(technicianId);
    assertThat(result.get(1).technicianId()).isEqualTo(secondTechnicianId);
  }

  @Test
  @DisplayName("17.1-SVC-018 P1 list on an unknown workorder throws WorkOrderNotFoundException")
  void listUnknownWorkorder() {
    when(workOrders.existsById("WO-2409NADA")).thenReturn(false);

    assertThatThrownBy(() -> service.list("WO-2409NADA"))
        .isInstanceOf(WorkOrderNotFoundException.class);
  }

  @Test
  @DisplayName("17.1-SVC-019 P0 active-duplicate pre-check rejects a second active assignment for the same tech")
  void activeDuplicatePreCheck() {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var entity = new WorkOrderEntity("WO-240900001", "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, UUID.randomUUID(),
        machineId, "desc", 0, null, technicianId, UUID.randomUUID(), NOW, NOW);
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(users.findById(technicianId)).thenReturn(Optional.of(technician(technicianId)));
    when(assignments.existsByWorkOrderIdAndTechnicianIdAndActiveTrue("WO-240900001", technicianId)).thenReturn(true);

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkAssignmentCommand(technicianId)))
        .isInstanceOf(AssignmentAlreadyExistsException.class);
    verify(assignments, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("17.1-SVC-020 P0 null scope collections are guarded without an NPE")
  void nullScopeCollectionsGuarded() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var entity = openEntity();
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(null, null, null));

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkAssignmentCommand(technicianId)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("17.1-SVC-021 P0 assigning to a deactivated user throws WorkorderForbiddenException")
  void inactiveAssigneeForbidden() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var entity = openEntity();
    var inactiveId = UUID.randomUUID();
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(users.findById(inactiveId)).thenReturn(Optional.of(
        new AuthUserEntity(inactiveId, "inactive@syncro.dev", "x", ApplicationRole.TECHNICIAN, false, NOW, NOW)));

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkAssignmentCommand(inactiveId)))
        .isInstanceOf(WorkorderForbiddenException.class);
    verify(assignments, never()).saveAndFlush(any());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private WorkOrderEntity openEntity() {
    return new WorkOrderEntity("WO-240900001", "INTERNAL", null, WorkOrderStatus.OPEN, UUID.randomUUID(),
        machineId, "desc", 0, null, null, UUID.randomUUID(), NOW, NOW);
  }

  private static AuthUserEntity technician(UUID id) {
    return new AuthUserEntity(id, "tech@syncro.dev", "x", ApplicationRole.TECHNICIAN, true, NOW, NOW);
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static MachineEntity machineWithPlant(UUID plantId, UUID groupId, UUID machineId) {
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(groupId, plant, "Group", NOW, NOW);
    return new MachineEntity(machineId, plant, group, "M-001", "Machine", MachineStatus.ACTIVE, null, null, null, List.of(), NOW, NOW);
  }
}
