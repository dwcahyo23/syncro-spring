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
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.application.WorkOrderService.ChildrenNotTerminalException;
import com.syncro.maintenance.application.WorkOrderService.DoneWithoutSessionReasonRequiredException;
import com.syncro.maintenance.application.WorkOrderService.InvalidStateTransitionException;
import com.syncro.maintenance.application.WorkOrderService.OverrideReasonRequiredException;
import com.syncro.maintenance.application.WorkOrderService.ProcurementRequestConflictException;
import com.syncro.maintenance.application.WorkOrderService.SessionOpenConflictException;
import com.syncro.maintenance.application.WorkOrderService.TransitionWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderForbiddenException;
import com.syncro.maintenance.domain.workorder.WorkOrderIdGenerator;
import com.syncro.maintenance.domain.workorder.WorkOrderStateMachine;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.RepairSessionEntity;
import com.syncro.maintenance.infrastructure.db.RepairSessionRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
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

@ExtendWith(MockitoExtension.class)
class WorkOrderTransitionServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
  private static final String WORKORDER_ID = "WO-2409-00001";

  @Mock
  private WorkOrderIdGenerator idGenerator;
  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private WorkOrderStatusHistoryRepository statusHistory;
  @Mock
  private WorkOrderCategoryRepository categories;
  @Mock
  private MachineRepository machines;
  @Mock
  private AuthUserRepository users;
  @Mock
  private OperationalScopeService scopes;
  @Mock
  private PlantScopeService plantScopes;
  @Mock
  private SparepartRequestReadinessPort sparepartReadiness;
  @Mock
  private AuditLogWriter auditLog;
  @Mock
  private RepairSessionRepository repairSessions;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID categoryId = UUID.randomUUID();
  private final UUID technicianId = UUID.randomUUID();

  private WorkOrderService service;
  private MachineEntity machine;

  @BeforeEach
  void setUp() {
    service = new WorkOrderService(idGenerator, workOrders, statusHistory, categories, machines, users, scopes,
        plantScopes, sparepartReadiness, auditLog, repairSessions, clock);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    lenient().when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(any())).thenReturn(Optional.empty());
    lenient().when(repairSessions.countByWorkOrderIdAndEndedAtIsNotNull(any())).thenReturn(1L);
  }

  // -------------------------------------------------------------------------
  // Valid transition matrix
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.3-SVC-001 P0 ASSIGNED→IN_PROGRESS by the assigned TECHNICIAN succeeds with MANUAL history + audit")
  void startByExecutor() {
    var user = assignedTechnician();
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.ASSIGNED, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, WORKORDER_ID, command(WorkOrderStatus.IN_PROGRESS));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
    verify(statusHistory).saveAndFlush(argThat(h -> "ASSIGNED".equals(h.getFromStatus())
        && "IN_PROGRESS".equals(h.getToStatus()) && "MANUAL".equals(h.getSource())
        && user.id().equals(h.getActor())));
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.entityType() == AuditEntityType.WORK_ORDER && r.entityLabel().equals(WORKORDER_ID)));
  }

  @Test
  @DisplayName("10.3-SVC-002 P0 IN_PROGRESS→DONE by the assigned executor succeeds")
  void completeByExecutor() {
    var user = assignedTechnician();
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.IN_PROGRESS, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, WORKORDER_ID, command(WorkOrderStatus.DONE));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.DONE);
    verify(statusHistory).saveAndFlush(argThat(h -> "IN_PROGRESS".equals(h.getFromStatus())
        && "DONE".equals(h.getToStatus())));
  }

  @Test
  @DisplayName("10.3-SVC-003 P0 ON_PROCUREMENT→IN_PROGRESS by the assigned executor succeeds")
  void resumeByExecutor() {
    var user = assignedTechnician();
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.ON_PROCUREMENT, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(sparepartReadiness.hasLiveNonReadyRequest(WORKORDER_ID)).thenReturn(false);
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, WORKORDER_ID, command(WorkOrderStatus.IN_PROGRESS));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
  }

  @Test
  @DisplayName("10.3-SVC-004 P0 ASSIGNED→IN_PROGRESS by an in-scope MAINTENANCE_LEADER (plant scope) succeeds")
  void startByPlantScopedLeader() {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.ASSIGNED, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, WORKORDER_ID, command(WorkOrderStatus.IN_PROGRESS));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
  }

  @Test
  @DisplayName("10.3-SVC-005 P1 IN_PROGRESS→ON_PROCUREMENT by an in-scope SECTION_LEADER succeeds")
  void placeOnProcurementBySectionLeader() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.IN_PROGRESS, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(sparepartReadiness.hasLiveNonReadyRequest(WORKORDER_ID)).thenReturn(false);
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, WORKORDER_ID, command(WorkOrderStatus.ON_PROCUREMENT));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.ON_PROCUREMENT);
  }

  @Test
  @DisplayName("10.3-SVC-006 P1 OPEN→CANCELLED by an in-scope SECTION_LEADER succeeds")
  void cancelOpenBySectionLeader() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.OPEN, null, null);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, WORKORDER_ID, command(WorkOrderStatus.CANCELLED));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.CANCELLED);
  }

  @Test
  @DisplayName("10.3-SVC-007 P1 ASSIGNED→CANCELLED by an in-scope MAINTENANCE_LEADER succeeds")
  void cancelAssignedByLeader() {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.ASSIGNED, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, WORKORDER_ID, command(WorkOrderStatus.CANCELLED));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.CANCELLED);
  }

  @Test
  @DisplayName("10.3-SVC-008 P2 DRAFT→OPEN by SUPER_ADMIN succeeds (machine edge, unreachable today)")
  void draftToOpen() {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.DRAFT, null, null);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, WORKORDER_ID, command(WorkOrderStatus.OPEN));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.OPEN);
  }

  @Test
  @DisplayName("10.3-SVC-009 P0 a STAFF_MAINTENANCE assignee may execute as executor")
  void staffAssigneeExecutes() {
    var user = new AuthenticatedUser(technicianId.toString(), "staff@syncro.dev", ApplicationRole.STAFF_MAINTENANCE);
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.ASSIGNED, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, WORKORDER_ID, command(WorkOrderStatus.IN_PROGRESS));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
  }

  // -------------------------------------------------------------------------
  // DONE gate (10.4 / FR-115)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.4-SVC-100 P0 IN_PROGRESS→DONE with no completed session and a blank reason is blocked")
  void doneWithoutSessionReasonRequired() {
    var user = assignedTechnician();
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.IN_PROGRESS, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.empty());
    when(repairSessions.countByWorkOrderIdAndEndedAtIsNotNull(WORKORDER_ID)).thenReturn(0L);

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID,
        new TransitionWorkOrderCommand(WorkOrderStatus.DONE, "  ", null)))
        .isInstanceOf(DoneWithoutSessionReasonRequiredException.class);
  }

  @Test
  @DisplayName("10.4-SVC-101 P0 IN_PROGRESS→DONE with no sessions and a non-blank reason persists done_reason")
  void doneWithReasonPersists() {
    var user = assignedTechnician();
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.IN_PROGRESS, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.empty());
    when(repairSessions.countByWorkOrderIdAndEndedAtIsNotNull(WORKORDER_ID)).thenReturn(0L);
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, WORKORDER_ID,
        new TransitionWorkOrderCommand(WorkOrderStatus.DONE, "  no technician available  ", null));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.DONE);
    assertThat(result.doneReason()).isEqualTo("no technician available");
    verify(auditLog).record(eq(user), argThat(r -> r.newValue() != null
        && "no technician available".equals(r.newValue().get("doneReason"))));
  }

  @Test
  @DisplayName("10.4-SVC-102 P0 IN_PROGRESS→DONE with an open session is blocked as SESSION_OPEN_CONFLICT")
  void doneWithOpenSessionConflict() {
    var user = assignedTechnician();
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.IN_PROGRESS, null, technicianId);
    var open = new RepairSessionEntity(UUID.randomUUID(), WORKORDER_ID, technicianId, "desc", NOW, null, null, NOW, NOW);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.of(open));

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID,
        new TransitionWorkOrderCommand(WorkOrderStatus.DONE, "spare", null)))
        .isInstanceOf(SessionOpenConflictException.class);
  }

  @Test
  @DisplayName("10.4-SVC-103 P0 IN_PROGRESS→DONE with a completed session succeeds and MTTR is untouched by the gate")
  void doneWithCompletedSession() {
    var user = assignedTechnician();
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.IN_PROGRESS, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.empty());
    when(repairSessions.countByWorkOrderIdAndEndedAtIsNotNull(WORKORDER_ID)).thenReturn(1L);
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, WORKORDER_ID, command(WorkOrderStatus.DONE));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.DONE);
    verify(workOrders).saveAndFlush(entity);
  }

  // -------------------------------------------------------------------------
  // Invalid transitions
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.3-SVC-010 P0 OPEN→IN_PROGRESS throws InvalidStateTransitionException")
  void invalidTransitionOpenToInProgress() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.OPEN, null, null);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID, command(WorkOrderStatus.IN_PROGRESS)))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  @DisplayName("10.3-SVC-011 P0 any→ASSIGNED is rejected — assignment is POST /{id}/assign")
  void transitionToAssignedRejected() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var open = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.OPEN, null, null);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(open));

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID, command(WorkOrderStatus.ASSIGNED)))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  @DisplayName("10.3-SVC-012 P0 transitions out of a terminal state are rejected")
  void terminalStateHasNoOutgoingEdges() {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var done = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.DONE, null, null);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(done));

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID, command(WorkOrderStatus.IN_PROGRESS)))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  // -------------------------------------------------------------------------
  // Actor matrix (executor vs leader; wrong role/scope/executor)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.3-SVC-013 P0 ASSIGNED→IN_PROGRESS by a different technician is forbidden")
  void notExecutorForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "other@syncro.dev", ApplicationRole.TECHNICIAN);
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.ASSIGNED, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID, command(WorkOrderStatus.IN_PROGRESS)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("10.3-SVC-014 P0 IN_PROGRESS→ON_PROCUREMENT by the assigned technician is forbidden (leader-only)")
  void procurementByTechnicianForbidden() {
    var user = assignedTechnician();
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.IN_PROGRESS, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID, command(WorkOrderStatus.ON_PROCUREMENT)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("10.3-SVC-015 P0 DONE→CLOSED by the assigned technician is forbidden (leader-only)")
  void closeByTechnicianForbidden() {
    var user = assignedTechnician();
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.DONE, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID, command(WorkOrderStatus.CLOSED)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("10.3-SVC-016 P0 an out-of-scope MAINTENANCE_LEADER is forbidden")
  void outOfScopeLeaderForbidden() {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.IN_PROGRESS, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(UUID.randomUUID()), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID, command(WorkOrderStatus.ON_PROCUREMENT)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("10.3-SVC-017 P0 SECTION_LEADER is group-only — plant scope does not grant transition access")
  void sectionLeaderGroupOnly() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.IN_PROGRESS, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(UUID.randomUUID()), Set.of()));

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID, command(WorkOrderStatus.ON_PROCUREMENT)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("10.3-SVC-018 P0 manual transitions on SYNCED workorders are forbidden")
  void syncedWorkorderForbidden() {
    var user = assignedTechnician();
    var entity = entity(WORKORDER_ID, "SYNCED", WorkOrderStatus.ASSIGNED, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID, command(WorkOrderStatus.IN_PROGRESS)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("10.3-SVC-019 P0 an unknown workorder throws WorkOrderNotFoundException")
  void transitionNotFound() {
    var user = user(ApplicationRole.SUPER_ADMIN);
    when(workOrders.findByIdForUpdate("WO-2409-NADA")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.transition(user, "WO-2409-NADA", command(WorkOrderStatus.OPEN)))
        .isInstanceOf(WorkOrderNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // Manual ON_PROCUREMENT guard (AD-5)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.3-SVC-020 P0 placement to ON_PROCUREMENT with a live non-ready request is a conflict")
  void manualPlacementConflict() {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.IN_PROGRESS, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(sparepartReadiness.hasLiveNonReadyRequest(WORKORDER_ID)).thenReturn(true);

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID, command(WorkOrderStatus.ON_PROCUREMENT)))
        .isInstanceOf(ProcurementRequestConflictException.class);
  }

  @Test
  @DisplayName("10.3-SVC-021 P1 manual resume from ON_PROCUREMENT with a live non-ready request is a conflict")
  void manualResumeConflict() {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.ON_PROCUREMENT, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(sparepartReadiness.hasLiveNonReadyRequest(WORKORDER_ID)).thenReturn(true);

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID, command(WorkOrderStatus.IN_PROGRESS)))
        .isInstanceOf(ProcurementRequestConflictException.class);
  }

  // -------------------------------------------------------------------------
  // Parent close (FR-120/AD-3)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.3-SVC-022 P0 closing a parent with a non-terminal child is blocked")
  void closeBlockedByOpenChild() {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    var parent = entity("WO-2409-PARENT", "INTERNAL", WorkOrderStatus.DONE, null, technicianId);
    var child = entity("WO-2409-CHILD", "INTERNAL", WorkOrderStatus.OPEN, "WO-2409-PARENT", null);
    when(workOrders.findByIdForUpdate("WO-2409-PARENT")).thenReturn(Optional.of(parent));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(workOrders.findByParentIdForUpdate("WO-2409-PARENT")).thenReturn(List.of(child));

    assertThatThrownBy(() -> service.transition(user, "WO-2409-PARENT", command(WorkOrderStatus.CLOSED)))
        .isInstanceOf(ChildrenNotTerminalException.class);
  }

  @Test
  @DisplayName("10.3-SVC-023 P0 closing a parent whose children are all CLOSED/CANCELLED succeeds")
  void closeWithTerminalChildren() {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    var parent = entity("WO-2409-PARENT", "INTERNAL", WorkOrderStatus.DONE, null, technicianId);
    var closed = entity("WO-2409-C1", "INTERNAL", WorkOrderStatus.CLOSED, "WO-2409-PARENT", null);
    var cancelled = entity("WO-2409-C2", "INTERNAL", WorkOrderStatus.CANCELLED, "WO-2409-PARENT", null);
    when(workOrders.findByIdForUpdate("WO-2409-PARENT")).thenReturn(Optional.of(parent));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(workOrders.findByParentIdForUpdate("WO-2409-PARENT")).thenReturn(List.of(closed, cancelled));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, "WO-2409-PARENT", command(WorkOrderStatus.CLOSED));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.CLOSED);
  }

  @Test
  @DisplayName("10.3-SVC-024 P0 SUPER_ADMIN may override a non-terminal child with an audit-logged reason")
  void closeOverrideBySuperAdmin() {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var parent = entity("WO-2409-PARENT", "INTERNAL", WorkOrderStatus.DONE, null, technicianId);
    var child = entity("WO-2409-C1", "INTERNAL", WorkOrderStatus.OPEN, "WO-2409-PARENT", null);
    when(workOrders.findByIdForUpdate("WO-2409-PARENT")).thenReturn(Optional.of(parent));
    when(workOrders.findByParentIdForUpdate("WO-2409-PARENT")).thenReturn(List.of(child));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, "WO-2409-PARENT",
        new TransitionWorkOrderCommand(WorkOrderStatus.CLOSED, null, "expedite delivery"));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.CLOSED);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.newValue() != null && "expedite delivery".equals(r.newValue().get("overrideReason"))));
  }

  @Test
  @DisplayName("10.3-SVC-025 P0 an override-eligible actor without a reason gets 400 OVERRIDE_REASON_REQUIRED")
  void closeOverrideWithoutReason() {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var parent = entity("WO-2409-PARENT", "INTERNAL", WorkOrderStatus.DONE, null, technicianId);
    var child = entity("WO-2409-C1", "INTERNAL", WorkOrderStatus.OPEN, "WO-2409-PARENT", null);
    when(workOrders.findByIdForUpdate("WO-2409-PARENT")).thenReturn(Optional.of(parent));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(workOrders.findByParentIdForUpdate("WO-2409-PARENT")).thenReturn(List.of(child));

    assertThatThrownBy(() -> service.transition(user, "WO-2409-PARENT", command(WorkOrderStatus.CLOSED)))
        .isInstanceOf(OverrideReasonRequiredException.class);
  }

  // -------------------------------------------------------------------------
  // Derived procurement recompute (AD-5)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.3-SVC-026 P0 recompute enters ON_PROCUREMENT with a DERIVED/SYSTEM history row")
  void recomputeEntersOnProcurement() {
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.IN_PROGRESS, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(sparepartReadiness.hasLiveNonReadyRequest(WORKORDER_ID)).thenReturn(true);
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.recomputeProcurementState(WORKORDER_ID);

    assertThat(entity.getStatus()).isEqualTo(WorkOrderStatus.ON_PROCUREMENT);
    verify(statusHistory).saveAndFlush(argThat(h -> "IN_PROGRESS".equals(h.getFromStatus())
        && "ON_PROCUREMENT".equals(h.getToStatus()) && "DERIVED".equals(h.getSource())
        && "SYSTEM".equals(h.getActor())));
  }

  @Test
  @DisplayName("10.3-SVC-027 P0 recompute resumes IN_PROGRESS with a DERIVED/SYSTEM history row")
  void recomputeResumesInProgress() {
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.ON_PROCUREMENT, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(sparepartReadiness.hasLiveNonReadyRequest(WORKORDER_ID)).thenReturn(false);
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.recomputeProcurementState(WORKORDER_ID);

    assertThat(entity.getStatus()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
    verify(statusHistory).saveAndFlush(argThat(h -> "ON_PROCUREMENT".equals(h.getFromStatus())
        && "IN_PROGRESS".equals(h.getToStatus()) && "DERIVED".equals(h.getSource())
        && "SYSTEM".equals(h.getActor())));
  }

  @Test
  @DisplayName("10.3-SVC-028 P0 recompute is idempotent — no history row when the state is unchanged")
  void recomputeNoop() {
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.IN_PROGRESS, null, technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(sparepartReadiness.hasLiveNonReadyRequest(WORKORDER_ID)).thenReturn(false);

    service.recomputeProcurementState(WORKORDER_ID);

    assertThat(entity.getStatus()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
    verify(statusHistory, never()).saveAndFlush(any());
    verify(workOrders, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("10.3-SVC-029 P2 recompute ignores statuses outside IN_PROGRESS/ON_PROCUREMENT")
  void recomputeIgnoresOtherStatuses() {
    var entity = entity(WORKORDER_ID, "INTERNAL", WorkOrderStatus.OPEN, null, null);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));

    service.recomputeProcurementState(WORKORDER_ID);

    assertThat(entity.getStatus()).isEqualTo(WorkOrderStatus.OPEN);
    verify(statusHistory, never()).saveAndFlush(any());
    verify(workOrders, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("10.3-SVC-030 P0 recompute on an unknown workorder throws WorkOrderNotFoundException")
  void recomputeNotFound() {
    when(workOrders.findByIdForUpdate("WO-2409-NADA")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.recomputeProcurementState("WO-2409-NADA"))
        .isInstanceOf(WorkOrderNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // State machine unit checks (mirror of the AD-4 table)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.3-SVC-031 P1 the state machine exposes the AD-4 transition table")
  void stateMachineTable() {
    assertThat(WorkOrderStateMachine.can(WorkOrderStatus.DRAFT, WorkOrderStatus.OPEN)).isTrue();
    assertThat(WorkOrderStateMachine.can(WorkOrderStatus.OPEN, WorkOrderStatus.ASSIGNED)).isTrue();
    assertThat(WorkOrderStateMachine.can(WorkOrderStatus.ASSIGNED, WorkOrderStatus.IN_PROGRESS)).isTrue();
    assertThat(WorkOrderStateMachine.can(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.ON_PROCUREMENT)).isTrue();
    assertThat(WorkOrderStateMachine.can(WorkOrderStatus.ON_PROCUREMENT, WorkOrderStatus.IN_PROGRESS)).isTrue();
    assertThat(WorkOrderStateMachine.can(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.DONE)).isTrue();
    assertThat(WorkOrderStateMachine.can(WorkOrderStatus.DONE, WorkOrderStatus.CLOSED)).isTrue();
    assertThat(WorkOrderStateMachine.can(WorkOrderStatus.OPEN, WorkOrderStatus.CANCELLED)).isTrue();
    assertThat(WorkOrderStateMachine.can(WorkOrderStatus.ASSIGNED, WorkOrderStatus.CANCELLED)).isTrue();
    assertThat(WorkOrderStateMachine.can(WorkOrderStatus.OPEN, WorkOrderStatus.IN_PROGRESS)).isFalse();
    assertThat(WorkOrderStateMachine.can(WorkOrderStatus.CLOSED, WorkOrderStatus.OPEN)).isFalse();
    assertThat(WorkOrderStateMachine.can(WorkOrderStatus.CANCELLED, WorkOrderStatus.OPEN)).isFalse();
    assertThat(WorkOrderStateMachine.can(WorkOrderStatus.DONE, WorkOrderStatus.IN_PROGRESS)).isFalse();
  }

  @Test
  @DisplayName("10.3-SVC-032 P1 the state machine marks DONE/CLOSED/CANCELLED terminal")
  void stateMachineTerminal() {
    assertThat(WorkOrderStateMachine.isTerminal(WorkOrderStatus.DONE)).isTrue();
    assertThat(WorkOrderStateMachine.isTerminal(WorkOrderStatus.CLOSED)).isTrue();
    assertThat(WorkOrderStateMachine.isTerminal(WorkOrderStatus.CANCELLED)).isTrue();
    assertThat(WorkOrderStateMachine.isTerminal(WorkOrderStatus.OPEN)).isFalse();
    assertThat(WorkOrderStateMachine.isTerminal(WorkOrderStatus.IN_PROGRESS)).isFalse();
    assertThat(WorkOrderStateMachine.isTerminal(WorkOrderStatus.ASSIGNED)).isFalse();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static TransitionWorkOrderCommand command(WorkOrderStatus toStatus) {
    return new TransitionWorkOrderCommand(toStatus, null, null);
  }

  private WorkOrderEntity entity(String id, String source, WorkOrderStatus status, String parentId,
      UUID assignedTechnicianId) {
    return new WorkOrderEntity(id, source, parentId, status, categoryId, machineId, "desc", 0, null,
        assignedTechnicianId, UUID.fromString(user(ApplicationRole.SECTION_LEADER).id()), NOW, NOW);
  }

  private AuthenticatedUser assignedTechnician() {
    return new AuthenticatedUser(technicianId.toString(), "tech@syncro.dev", ApplicationRole.TECHNICIAN);
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static MachineEntity machineWithPlant(UUID plantId, UUID groupId, UUID machineId) {
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(groupId, plant, "Group", NOW, NOW);
    return new MachineEntity(machineId, plant, group, "M-001", "Machine", MachineStatus.ACTIVE, null, null, null,
        List.of(), NOW, NOW);
  }
}