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
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.application.WorkOrderService.AssignWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.BreakdownCategoryRequiredException;
import com.syncro.maintenance.application.WorkOrderService.CreateWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.InvalidStateTransitionException;
import com.syncro.maintenance.application.WorkOrderService.SelfAssignmentForbiddenException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderCategoryNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderMachineNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderParentNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.StopTimeReasonRequiredException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderUserNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderForbiddenException;
import com.syncro.maintenance.domain.workorder.WorkOrderIdGenerator;
import com.syncro.maintenance.domain.workorder.WorkOrderIdGenerator.WorkorderIdExhaustedException;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.RepairSessionRepository;
import com.syncro.maintenance.infrastructure.db.WorkLogRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
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
class WorkOrderServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

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
  private final UUID assigneeId = UUID.randomUUID();

  private WorkOrderService service;
  private MachineEntity machine;
  private WorkOrderCategoryEntity category;

  @BeforeEach
  void setUp() {
    service = new WorkOrderService(idGenerator, workOrders, statusHistory, categories, machines, users, scopes,
        plantScopes, sparepartReadiness, auditLog, repairSessions, workLogs, clock);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    category = new WorkOrderCategoryEntity(categoryId, "01", "Breakdown", UUID.randomUUID(), NOW, NOW);
  }

  @Test
  @DisplayName("10.2-SVC-001 P0 SECTION_LEADER creates a workorder with a valid machine in scope")
  void createOk() {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(categories.findByCode("01")).thenReturn(Optional.of(category));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(idGenerator.nextId()).thenReturn("WO-240900001");
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.create(user, new CreateWorkOrderCommand("01", machineId, "desc", null, null));

    assertThat(result.workorder().id()).isEqualTo("WO-240900001");
    assertThat(result.workorder().source()).isEqualTo("INTERNAL");
    assertThat(result.workorder().status()).isEqualTo(WorkOrderStatus.OPEN);
    assertThat(result.workorder().machineId()).isEqualTo(machineId);
    assertThat(result.workorder().categoryId()).isEqualTo(categoryId);
    assertThat(result.workorder().createdBy()).isEqualTo(UUID.fromString(user.id()));
    assertThat(result.replay()).isFalse();

    verify(statusHistory).saveAndFlush(argThat(h -> h.getFromStatus() == null && "OPEN".equals(h.getToStatus())));
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.CREATE
        && r.entityType() == AuditEntityType.WORK_ORDER
        && r.entityLabel().equals("WO-240900001")));
  }

  @Test
  @DisplayName("10.2-SVC-002 P0 idempotency replay within 5 min returns the same workorder")
  void createReplay() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var userId = UUID.fromString(user.id());
    var existing = new WorkOrderEntity("WO-240900001", "INTERNAL", null, WorkOrderStatus.OPEN, categoryId,
        machineId, "desc", 0, "key-123", null, userId, NOW, NOW);
    when(workOrders.findByIdempotencyKeyAndCreatedByAndCreatedAtAfter("key-123", userId, NOW.minus(Duration.ofMinutes(5))))
        .thenReturn(Optional.of(existing));

    var result = service.create(user, new CreateWorkOrderCommand("01", machineId, "desc", null, "key-123"));

    assertThat(result.workorder().id()).isEqualTo("WO-240900001");
    assertThat(result.replay()).isTrue();
    verify(workOrders, never()).saveAndFlush(any());
    verify(statusHistory, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("10.2-SVC-003 P0 expired idempotency key creates a new workorder")
  void createExpiredKey() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var userId = UUID.fromString(user.id());
    when(workOrders.findByIdempotencyKeyAndCreatedByAndCreatedAtAfter("key-123", userId, NOW.minus(Duration.ofMinutes(5))))
        .thenReturn(Optional.empty());
    when(categories.findByCode("01")).thenReturn(Optional.of(category));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(idGenerator.nextId()).thenReturn("WO-240900002");
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.create(user, new CreateWorkOrderCommand("01", machineId, "desc", null, "key-123"));

    assertThat(result.workorder().id()).isEqualTo("WO-240900002");
  }

  @Test
  @DisplayName("10.2-SVC-004 P0 SECTION_LEADER with machine group out of scope is forbidden")
  void createSectionLeaderOutOfScope() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var otherGroupId = UUID.randomUUID();
    when(categories.findByCode("01")).thenReturn(Optional.of(category));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(otherGroupId), Set.of()));

    assertThatThrownBy(() -> service.create(user, new CreateWorkOrderCommand("01", machineId, "desc", null, null)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("10.2-SVC-005 P0 PRODUCTION_LEADER creates a breakdown workorder in plant scope")
  void createProductionLeaderOk() {
    var user = user(ApplicationRole.PRODUCTION_LEADER);
    when(categories.findByCode("01")).thenReturn(Optional.of(category));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(idGenerator.nextId()).thenReturn("WO-240900001");
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.create(user, new CreateWorkOrderCommand("01", machineId, "desc", null, null));

    assertThat(result.workorder().id()).isEqualTo("WO-240900001");
    verify(plantScopes).requirePlantAccess(user, plantId);
  }

  @Test
  @DisplayName("10.2-SVC-006 P0 PRODUCTION_LEADER with non-01 category throws BreakdownCategoryRequiredException")
  void createProductionLeaderWrongCategory() {
    var user = user(ApplicationRole.PRODUCTION_LEADER);
    var otherCategory = new WorkOrderCategoryEntity(UUID.randomUUID(), "02", "Preventive", UUID.randomUUID(), NOW, NOW);
    when(categories.findByCode("02")).thenReturn(Optional.of(otherCategory));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));

    assertThatThrownBy(() -> service.create(user, new CreateWorkOrderCommand("02", machineId, "desc", null, null)))
        .isInstanceOf(BreakdownCategoryRequiredException.class);
  }

  @Test
  @DisplayName("10.2-SVC-007 P0 TECHNICIAN is forbidden from creating")
  void createTechnicianForbidden() {
    var user = user(ApplicationRole.TECHNICIAN);

    assertThatThrownBy(() -> service.create(user, new CreateWorkOrderCommand("01", machineId, "desc", null, null)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("10.2-SVC-008 P1 child workorder with parent in scope is created")
  void createChildOk() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var parentMachine = machineWithPlant(plantId, groupId, UUID.randomUUID());
    var parent = new WorkOrderEntity("WO-2409PARENT", "INTERNAL", null, WorkOrderStatus.OPEN, categoryId,
        parentMachine.getId(), "parent", 0, null, null, UUID.randomUUID(), NOW, NOW);

    when(categories.findByCode("01")).thenReturn(Optional.of(category));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(workOrders.findById("WO-2409PARENT")).thenReturn(Optional.of(parent));
    when(machines.findByIdWithPlantAndGroup(parentMachine.getId())).thenReturn(Optional.of(parentMachine));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(idGenerator.nextId()).thenReturn("WO-240900001");
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.create(user, new CreateWorkOrderCommand("01", machineId, "desc", "WO-2409PARENT", null));

    assertThat(result.workorder().parentId()).isEqualTo("WO-2409PARENT");
  }

  @Test
  @DisplayName("10.2-SVC-009 P1 child workorder with parent machine out of scope is forbidden")
  void createChildOutOfScope() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var otherGroupId = UUID.randomUUID();
    var parentMachine = machineWithPlant(plantId, otherGroupId, UUID.randomUUID());
    var parent = new WorkOrderEntity("WO-2409PARENT", "INTERNAL", null, WorkOrderStatus.OPEN, categoryId,
        parentMachine.getId(), "parent", 0, null, null, UUID.randomUUID(), NOW, NOW);

    when(categories.findByCode("01")).thenReturn(Optional.of(category));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(workOrders.findById("WO-2409PARENT")).thenReturn(Optional.of(parent));
    when(machines.findByIdWithPlantAndGroup(parentMachine.getId())).thenReturn(Optional.of(parentMachine));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));

    assertThatThrownBy(() -> service.create(user, new CreateWorkOrderCommand("01", machineId, "desc", "WO-2409PARENT", null)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("10.2-SVC-010 P0 missing category throws WorkOrderCategoryNotFoundException")
  void createMissingCategory() {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(categories.findByCode("99")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(user, new CreateWorkOrderCommand("99", machineId, "desc", null, null)))
        .isInstanceOf(WorkOrderCategoryNotFoundException.class);
  }

  @Test
  @DisplayName("10.2-SVC-011 P0 missing machine throws WorkOrderMachineNotFoundException")
  void createMissingMachine() {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(categories.findByCode("01")).thenReturn(Optional.of(category));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(user, new CreateWorkOrderCommand("01", machineId, "desc", null, null)))
        .isInstanceOf(WorkOrderMachineNotFoundException.class);
  }

  @Test
  @DisplayName("10.2-SVC-012 P0 missing parent throws WorkOrderParentNotFoundException")
  void createMissingParent() {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(categories.findByCode("01")).thenReturn(Optional.of(category));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(workOrders.findById("WO-2409NOPE")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(user, new CreateWorkOrderCommand("01", machineId, "desc", "WO-2409NOPE", null)))
        .isInstanceOf(WorkOrderParentNotFoundException.class);
  }

  @Test
  @DisplayName("10.2-SVC-013 P0 id generator exhaustion throws WorkorderIdExhaustedException")
  void createIdExhausted() {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(categories.findByCode("01")).thenReturn(Optional.of(category));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(idGenerator.nextId()).thenThrow(new WorkorderIdExhaustedException());

    assertThatThrownBy(() -> service.create(user, new CreateWorkOrderCommand("01", machineId, "desc", null, null)))
        .isInstanceOf(WorkorderIdExhaustedException.class);
  }

  // -------------------------------------------------------------------------
  // Assign tests
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.2-SVC-014 P0 SECTION_LEADER assigns an OPEN workorder to a different user")
  void assignOk() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var entity = new WorkOrderEntity("WO-240900001", "INTERNAL", null, WorkOrderStatus.OPEN, categoryId,
        machineId, "desc", 0, null, null, UUID.fromString(user.id()), NOW, NOW);
    when(workOrders.findById("WO-240900001")).thenReturn(Optional.of(entity));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(users.findById(assigneeId)).thenReturn(Optional.of(technician(assigneeId)));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.assign(user, "WO-240900001", new AssignWorkOrderCommand(assigneeId));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
    assertThat(result.assignedTechnicianId()).isEqualTo(assigneeId);
    verify(statusHistory).saveAndFlush(argThat(h -> "OPEN".equals(h.getFromStatus()) && "IN_PROGRESS".equals(h.getToStatus())));
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.entityType() == AuditEntityType.WORK_ORDER
        && r.entityLabel().equals("WO-240900001")));
  }

  @Test
  @DisplayName("10.2-SVC-015 P0 assigning a non-OPEN workorder throws InvalidStateTransitionException")
  void assignWrongState() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var entity = new WorkOrderEntity("WO-240900001", "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, categoryId,
        machineId, "desc", 0, null, assigneeId, UUID.randomUUID(), NOW, NOW);
    when(workOrders.findById("WO-240900001")).thenReturn(Optional.of(entity));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkOrderCommand(assigneeId)))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  @DisplayName("10.2-SVC-016 P0 SECTION_LEADER self-assignment throws SelfAssignmentForbiddenException")
  void assignSelf() {
    var selfId = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");
    var user = new AuthenticatedUser(selfId.toString(), "leader@syncro.dev", ApplicationRole.SECTION_LEADER);
    var entity = new WorkOrderEntity("WO-240900001", "INTERNAL", null, WorkOrderStatus.OPEN, categoryId,
        machineId, "desc", 0, null, null, selfId, NOW, NOW);
    when(workOrders.findById("WO-240900001")).thenReturn(Optional.of(entity));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(users.findById(selfId)).thenReturn(Optional.of(technician(selfId)));

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkOrderCommand(selfId)))
        .isInstanceOf(SelfAssignmentForbiddenException.class);
  }

  @Test
  @DisplayName("10.2-SVC-017 P0 assigning an unknown workorder throws WorkOrderNotFoundException")
  void assignNotFound() {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(workOrders.findById("WO-2409NADA")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.assign(user, "WO-2409NADA", new AssignWorkOrderCommand(assigneeId)))
        .isInstanceOf(WorkOrderNotFoundException.class);
  }

  @Test
  @DisplayName("10.2-SVC-018 P0 assigning with a non-existent user throws WorkOrderUserNotFoundException")
  void assignUserNotFound() {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var entity = new WorkOrderEntity("WO-240900001", "INTERNAL", null, WorkOrderStatus.OPEN, categoryId,
        machineId, "desc", 0, null, null, UUID.randomUUID(), NOW, NOW);
    when(workOrders.findById("WO-240900001")).thenReturn(Optional.of(entity));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(users.findById(assigneeId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkOrderCommand(assigneeId)))
        .isInstanceOf(WorkOrderUserNotFoundException.class);
  }

  @Test
  @DisplayName("10.2-SVC-019 P1 STAFF_MAINTENANCE cannot assign (requires leadership role)")
  void assignStaffForbidden() {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);

    assertThatThrownBy(() -> service.assign(user, "WO-240900001", new AssignWorkOrderCommand(assigneeId)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("10.2-SVC-020 P1 STAFF_MAINTENANCE with plant scope can create a workorder")
  void createStaffWithPlantAccess() {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(categories.findByCode("01")).thenReturn(Optional.of(category));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(idGenerator.nextId()).thenReturn("WO-240900001");
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.create(user, new CreateWorkOrderCommand("01", machineId, "desc", null, null));

    assertThat(result.workorder().id()).isEqualTo("WO-240900001");
    verify(plantScopes).requirePlantAccess(user, plantId);
  }

  @Test
  @DisplayName("10.2-SVC-021 P1 MANAGER_MAINTENANCE without plant access on the machine is forbidden")
  void createManagerWithoutPlantAccess() {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(categories.findByCode("01")).thenReturn(Optional.of(category));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    org.mockito.Mockito.doThrow(new PlantScopeService.PlantAccessDeniedException())
        .when(plantScopes).requirePlantAccess(user, plantId);

    assertThatThrownBy(() -> service.create(user, new CreateWorkOrderCommand("01", machineId, "desc", null, null)))
        .isInstanceOf(PlantScopeService.PlantAccessDeniedException.class);
  }

  // -------------------------------------------------------------------------
  // Breakdown stop-time DONE gate (10.6 / FR-122)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.6-SVC-100 P0 breakdown DONE without a stop-time reason is blocked")
  void breakdownDoneWithoutStopTimeReason() {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var entity = new WorkOrderEntity("WO-240900001", "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, categoryId,
        machineId, "desc", 0, null, null, UUID.randomUUID(), NOW, NOW);
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(categories.findById(categoryId)).thenReturn(Optional.of(category));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull("WO-240900001")).thenReturn(Optional.empty());
    when(repairSessions.countByWorkOrderIdAndEndedAtIsNotNull("WO-240900001")).thenReturn(1L);

    assertThatThrownBy(() -> service.transition(user, "WO-240900001",
        new WorkOrderService.TransitionWorkOrderCommand(WorkOrderStatus.PENDING_REVIEW, null, null)))
        .isInstanceOf(StopTimeReasonRequiredException.class);
  }

  @Test
  @DisplayName("10.6-SVC-101 P0 breakdown DONE with a stop-time reason proceeds")
  void breakdownDoneWithStopTimeReason() {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var entity = new WorkOrderEntity("WO-240900001", "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, categoryId,
        machineId, "desc", 0, null, null, UUID.randomUUID(), NOW, NOW);
    entity.applyReport(null, null, null, null, null, null, null, null, "ELECTRIC", "Bearing worn");
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull("WO-240900001")).thenReturn(Optional.empty());
    when(repairSessions.countByWorkOrderIdAndEndedAtIsNotNull("WO-240900001")).thenReturn(1L);
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, "WO-240900001",
        new WorkOrderService.TransitionWorkOrderCommand(WorkOrderStatus.PENDING_REVIEW, null, null));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.PENDING_REVIEW);
  }

  @Test
  @DisplayName("10.6-SVC-102 P0 non-breakdown DONE without a stop-time reason proceeds")
  void nonBreakdownDoneWithoutStopTimeReason() {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var preventiveCategoryId = UUID.randomUUID();
    var entity = new WorkOrderEntity("WO-240900001", "INTERNAL", null, WorkOrderStatus.IN_PROGRESS,
        preventiveCategoryId, machineId, "desc", 0, null, null, UUID.randomUUID(), NOW, NOW);
    var preventive = new WorkOrderCategoryEntity(preventiveCategoryId, "02", "Preventive", UUID.randomUUID(), NOW, NOW);
    when(workOrders.findByIdForUpdate("WO-240900001")).thenReturn(Optional.of(entity));
    when(categories.findById(preventiveCategoryId)).thenReturn(Optional.of(preventive));
    when(repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull("WO-240900001")).thenReturn(Optional.empty());
    when(repairSessions.countByWorkOrderIdAndEndedAtIsNotNull("WO-240900001")).thenReturn(1L);
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.transition(user, "WO-240900001",
        new WorkOrderService.TransitionWorkOrderCommand(WorkOrderStatus.PENDING_REVIEW, null, null));

    assertThat(result.status()).isEqualTo(WorkOrderStatus.PENDING_REVIEW);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

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

  @Test
  @DisplayName("11.3-SVC-010 P0 createSystem creates an INTERNAL workorder linked to the preventive schedule with SYSTEM actor")
  void createSystemOk() {
    var preventiveCategory = new WorkOrderCategoryEntity(UUID.randomUUID(), "02", "Preventive", null, NOW, NOW);
    var scheduleId = UUID.randomUUID();
    when(categories.findByCode("02")).thenReturn(Optional.of(preventiveCategory));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    when(idGenerator.nextId()).thenReturn("WO-260900001");
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var id = service.createSystem(machineId, "02", "Preventive: Monthly lube due 2026-09-15", scheduleId);

    assertThat(id).isEqualTo("WO-260900001");
    var captor = ArgumentCaptor.forClass(WorkOrderEntity.class);
    verify(workOrders).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getSource()).isEqualTo("INTERNAL");
    assertThat(captor.getValue().getPreventiveScheduleId()).isEqualTo(scheduleId);
    assertThat(captor.getValue().getStatus()).isEqualTo(WorkOrderStatus.OPEN);
    // SYSTEM history row: source DERIVED, actor SYSTEM.
    verify(statusHistory).saveAndFlush(argThat(row ->
        "SYSTEM".equals(row.getActor()) && "DERIVED".equals(row.getSource())
            && "WO-260900001".equals(row.getWorkOrderId())));
    // SYSTEM audit via recordSystem.
    verify(auditLog).recordSystem(argThat(r ->
        r.action() == AuditAction.CREATE && r.entityType() == AuditEntityType.WORK_ORDER));
  }

  @Test
  @DisplayName("11.3-SVC-011 P0 createSystem with missing category throws category-not-found")
  void createSystemMissingCategory() {
    when(categories.findByCode("02")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.createSystem(machineId, "02", "Preventive x", UUID.randomUUID()))
        .isInstanceOf(WorkOrderService.WorkOrderCategoryNotFoundException.class);
    verify(workOrders, org.mockito.Mockito.never()).saveAndFlush(any());
  }
}