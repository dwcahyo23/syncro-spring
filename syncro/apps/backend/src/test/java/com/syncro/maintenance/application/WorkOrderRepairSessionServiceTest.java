package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import com.syncro.maintenance.application.WorkOrderService.DoneWithoutSessionReasonRequiredException;
import com.syncro.maintenance.application.WorkOrderService.NoOpenSessionException;
import com.syncro.maintenance.application.WorkOrderService.RepairSessionsResult;
import com.syncro.maintenance.application.WorkOrderService.SessionAlreadyOpenException;
import com.syncro.maintenance.application.WorkOrderService.SessionOpenConflictException;
import com.syncro.maintenance.application.WorkOrderService.SessionOverlapException;
import com.syncro.maintenance.application.WorkOrderService.StartSessionCommand;
import com.syncro.maintenance.application.WorkOrderService.TransitionWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderForbiddenException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderNotInProgressException;
import com.syncro.maintenance.domain.workorder.WorkOrderIdGenerator;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.RepairSessionEntity;
import com.syncro.maintenance.infrastructure.db.RepairSessionRepository;
import com.syncro.maintenance.infrastructure.db.WorkLogRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Duration;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkOrderRepairSessionServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
  private static final String WORKORDER_ID = "WO-240900001";

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
  @Mock
  private WorkLogRepository workLogs;

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
        plantScopes, sparepartReadiness, auditLog, repairSessions, workLogs, clock);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
  }

  // -------------------------------------------------------------------------
  // Start session
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.4-SVC-001 P0 assigned executor starts the first session and response time is computed")
  void startOkComputesResponseTime() {
    var user = assignedTechnician();
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.empty());
    when(statusHistory.findFirstOpenTransitionedAt(WORKORDER_ID))
        .thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(10))));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var captor = ArgumentCaptor.forClass(RepairSessionEntity.class);
    when(repairSessions.saveAndFlush(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
    when(repairSessions.findByWorkOrderIdOrderByStartedAtAsc(WORKORDER_ID))
        .thenAnswer(invocation -> new java.util.ArrayList<>(captor.getAllValues()));

    RepairSessionsResult result = service.startSession(user, WORKORDER_ID, new StartSessionCommand("diagnosis"));

    assertThat(result.workOrder().responseTimeMinutes()).isEqualTo(10L);
    assertThat(result.sessions()).hasSize(1);
    var saved = captor.getValue();
    assertThat(saved.getWorkOrderId()).isEqualTo(WORKORDER_ID);
    assertThat(saved.getTechnicianId()).isEqualTo(technicianId);
    assertThat(saved.getDescription()).isEqualTo("diagnosis");
    assertThat(saved.getStartedAt()).isEqualTo(NOW);
    assertThat(saved.getEndedAt()).isNull();
    verify(repairSessions).saveAndFlush(any(RepairSessionEntity.class));
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.CREATE
        && r.entityType() == AuditEntityType.REPAIR_SESSION && r.entityLabel().equals(WORKORDER_ID)));
  }

  @Test
  @DisplayName("10.4-SVC-002 P0 response time is only computed on the first start")
  void startDoesNotRecomputeResponseTime() {
    var user = assignedTechnician();
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    entity.setResponseTimeMinutes(15L);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.empty());
    when(repairSessions.findByWorkOrderIdOrderByStartedAtAsc(WORKORDER_ID)).thenReturn(List.of());
    when(repairSessions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    RepairSessionsResult result = service.startSession(user, WORKORDER_ID, new StartSessionCommand("second"));

    assertThat(result.workOrder().responseTimeMinutes()).isEqualTo(15L);
    verify(statusHistory, org.mockito.Mockito.never()).findFirstOpenTransitionedAt(any());
  }

  @Test
  @DisplayName("10.4-SVC-003 P0 a second open session is rejected as SESSION_ALREADY_OPEN")
  void startAlreadyOpen() {
    var user = assignedTechnician();
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    var open = new RepairSessionEntity(UUID.randomUUID(), WORKORDER_ID, technicianId, "desc", NOW, null, null, NOW, NOW);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.of(open));

    assertThatThrownBy(() -> service.startSession(user, WORKORDER_ID, new StartSessionCommand("again")))
        .isInstanceOf(SessionAlreadyOpenException.class);
  }

  @Test
  @DisplayName("10.4-SVC-004 P0 a start overlapping a session that ended in the future is rejected")
  void startOverlap() {
    var user = assignedTechnician();
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    var closedInFuture = new RepairSessionEntity(UUID.randomUUID(), WORKORDER_ID, technicianId, "desc",
        NOW.minus(Duration.ofMinutes(30)), NOW.plus(Duration.ofMinutes(10)), 30L, NOW, NOW);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.empty());
    when(repairSessions.findByWorkOrderIdOrderByStartedAtAsc(WORKORDER_ID)).thenReturn(List.of(closedInFuture));

    assertThatThrownBy(() -> service.startSession(user, WORKORDER_ID, new StartSessionCommand("overlap")))
        .isInstanceOf(SessionOverlapException.class);
  }

  @Test
  @DisplayName("10.4-SVC-005 P0 start on a non-IN_PROGRESS workorder is rejected")
  void startNotInProgress() {
    var user = assignedTechnician();
    var entity = entity(WorkOrderStatus.OPEN);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.startSession(user, WORKORDER_ID, new StartSessionCommand("early")))
        .isInstanceOf(WorkorderNotInProgressException.class);
  }

  @Test
  @DisplayName("10.4-SVC-006 P0 start by a non-executor non-leader is forbidden")
  void startForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "other@syncro.dev", ApplicationRole.TECHNICIAN);
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.startSession(user, WORKORDER_ID, new StartSessionCommand("nope")))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("10.4-SVC-007 P0 an unknown workorder is 404 WORKORDER_NOT_FOUND")
  void startNotFound() {
    var user = assignedTechnician();
    when(workOrders.findByIdForUpdate("WO-2409NADA")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.startSession(user, "WO-2409NADA", new StartSessionCommand("x")))
        .isInstanceOf(WorkOrderNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // Stop session
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.4-SVC-008 P0 stop closes the session, recomputes MTTR and audits UPDATE")
  void stopOkRecomputesMttr() {
    var user = assignedTechnician();
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    var open = new RepairSessionEntity(UUID.randomUUID(), WORKORDER_ID, technicianId, "desc",
        NOW.minus(Duration.ofMinutes(45)), null, null, NOW, NOW);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.of(open));
    when(repairSessions.sumCompletedDuration(WORKORDER_ID)).thenReturn(60L);
    when(repairSessions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(repairSessions.findByWorkOrderIdOrderByStartedAtAsc(WORKORDER_ID)).thenReturn(List.of(open));

    RepairSessionsResult result = service.stopSession(user, WORKORDER_ID);

    assertThat(open.getEndedAt()).isEqualTo(NOW);
    assertThat(open.getDurationMinutes()).isEqualTo(45L);
    assertThat(result.workOrder().mttrMinutes()).isEqualTo(60L);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.entityType() == AuditEntityType.REPAIR_SESSION && r.entityLabel().equals(WORKORDER_ID)
        && r.previousValue() != null && r.newValue() != null
        && Long.valueOf(45L).equals(r.newValue().get("durationMinutes"))));
  }

  @Test
  @DisplayName("10.4-SVC-009 P0 stop with no open session is rejected")
  void stopNoOpen() {
    var user = assignedTechnician();
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.stopSession(user, WORKORDER_ID))
        .isInstanceOf(NoOpenSessionException.class);
  }

  @Test
  @DisplayName("10.4-SVC-010 P0 stop by a non-executor non-leader is forbidden")
  void stopForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "other@syncro.dev", ApplicationRole.TECHNICIAN);
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.stopSession(user, WORKORDER_ID))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  // -------------------------------------------------------------------------
  // List sessions
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.4-SVC-011 P0 list returns sessions ordered by startedAt asc")
  void listOrdered() {
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var first = new RepairSessionEntity(UUID.randomUUID(), WORKORDER_ID, technicianId, "first",
        NOW.minus(Duration.ofMinutes(30)), NOW, 30L, NOW, NOW);
    var second = new RepairSessionEntity(UUID.randomUUID(), WORKORDER_ID, technicianId, "second",
        NOW.minus(Duration.ofMinutes(10)), NOW, 10L, NOW, NOW);
    when(repairSessions.findByWorkOrderIdOrderByStartedAtAsc(WORKORDER_ID)).thenReturn(List.of(first, second));

    RepairSessionsResult result = service.listSessions(WORKORDER_ID);

    assertThat(result.workOrder().id()).isEqualTo(WORKORDER_ID);
    assertThat(result.sessions()).hasSize(2);
    assertThat(result.sessions().get(0).description()).isEqualTo("first");
    assertThat(result.sessions().get(1).description()).isEqualTo("second");
  }

  // -------------------------------------------------------------------------
  // DONE gate (FR-115) — same behavior exercised from the session service surface
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.4-SVC-012 P0 DONE with no completed session and blank reason is blocked")
  void doneWithoutSessionReasonRequired() {
    var user = assignedTechnician();
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.empty());
    when(repairSessions.countByWorkOrderIdAndEndedAtIsNotNull(WORKORDER_ID)).thenReturn(0L);

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID,
        new TransitionWorkOrderCommand(WorkOrderStatus.PENDING_REVIEW, "   ", null)))
        .isInstanceOf(DoneWithoutSessionReasonRequiredException.class);
  }

  @Test
  @DisplayName("10.4-SVC-013 P0 DONE with no sessions and a non-blank reason persists done_reason")
  void doneWithReasonPersists() {
    var user = assignedTechnician();
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.empty());
    when(repairSessions.countByWorkOrderIdAndEndedAtIsNotNull(WORKORDER_ID)).thenReturn(0L);
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, WORKORDER_ID,
        new TransitionWorkOrderCommand(WorkOrderStatus.PENDING_REVIEW, "waiting on part", null));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.PENDING_REVIEW);
    assertThat(result.doneReason()).isEqualTo("waiting on part");
    verify(auditLog).record(eq(user), argThat(r -> r.newValue() != null
        && "waiting on part".equals(r.newValue().get("doneReason"))));
  }

  @Test
  @DisplayName("10.4-SVC-014 P0 DONE while a session is open is blocked as SESSION_OPEN_CONFLICT")
  void doneWithOpenSessionConflict() {
    var user = assignedTechnician();
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    var open = new RepairSessionEntity(UUID.randomUUID(), WORKORDER_ID, technicianId, "desc", NOW, null, null, NOW, NOW);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.of(open));

    assertThatThrownBy(() -> service.transition(user, WORKORDER_ID,
        new TransitionWorkOrderCommand(WorkOrderStatus.PENDING_REVIEW, "spare", null)))
        .isInstanceOf(SessionOpenConflictException.class);
  }

  @Test
  @DisplayName("10.4-SVC-015 P1 an in-scope MAINTENANCE_LEADER (plant scope) may start a session")
  void startByPlantScopedLeader() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "lead@syncro.dev", ApplicationRole.MAINTENANCE_LEADER);
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(WORKORDER_ID)).thenReturn(Optional.empty());
    var saved = new java.util.ArrayList<RepairSessionEntity>();
    when(repairSessions.saveAndFlush(any())).thenAnswer(invocation -> {
      var session = (RepairSessionEntity) invocation.getArgument(0);
      saved.add(session);
      return session;
    });
    when(repairSessions.findByWorkOrderIdOrderByStartedAtAsc(WORKORDER_ID))
        .thenAnswer(invocation -> List.copyOf(saved));

    var result = service.startSession(user, WORKORDER_ID, new StartSessionCommand("leader"));

    assertThat(result.sessions()).hasSize(1);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private WorkOrderEntity entity(WorkOrderStatus status) {
    return new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, status, categoryId, machineId, "desc", 0, null,
        technicianId, UUID.fromString(assignedTechnician().id()), NOW, NOW);
  }

  private AuthenticatedUser assignedTechnician() {
    return new AuthenticatedUser(technicianId.toString(), "tech@syncro.dev", ApplicationRole.TECHNICIAN);
  }

  private static MachineEntity machineWithPlant(UUID plantId, UUID groupId, UUID machineId) {
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(groupId, plant, "Group", NOW, NOW);
    return new MachineEntity(machineId, plant, group, "M-001", "Machine", MachineStatus.ACTIVE, null, null, null,
        List.of(), NOW, NOW);
  }
}
