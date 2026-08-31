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
import com.syncro.maintenance.application.WorkOrderTodoService.CreateTodoCommand;
import com.syncro.maintenance.application.WorkOrderTodoService.TodoAlreadyCompletedException;
import com.syncro.maintenance.application.WorkOrderTodoService.TodoForbiddenException;
import com.syncro.maintenance.application.WorkOrderTodoService.TodoNotFoundException;
import com.syncro.maintenance.application.WorkOrderTodoService.TodoWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderTodoService.WorkOrderTerminalException;
import com.syncro.maintenance.application.WorkOrderTodoService.WorkOrderTodoValidationException;
import com.syncro.maintenance.domain.workorder.TodoStatus;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderKanbanRow;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderTodoEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderTodoRepository;
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
class WorkOrderTodoServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
  private static final String WORKORDER_ID = "WO-2409-00001";

  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private WorkOrderTodoRepository todos;
  @Mock
  private MachineRepository machines;
  @Mock
  private AuthUserRepository users;
  @Mock
  private AuditLogWriter auditLog;
  @Mock
  private OperationalScopeService scopes;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID technicianId = UUID.randomUUID();

  private WorkOrderTodoService service;
  private MachineEntity machine;

  @BeforeEach
  void setUp() {
    service = new WorkOrderTodoService(workOrders, todos, machines, users, auditLog, scopes, clock);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
  }

  // -------------------------------------------------------------------------
  // Create todo
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.7-SVC-001 P0 assigned executor creates a todo and audits CREATE")
  void createTodoOk() {
    var user = assignedTechnician();
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(todos.findByWorkorderIdOrderBySortOrderAsc(WORKORDER_ID)).thenReturn(List.of());
    when(todos.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var todo = service.create(user, WORKORDER_ID, new CreateTodoCommand("Fix bearing", null, null));

    assertThat(todo.title()).isEqualTo("Fix bearing");
    assertThat(todo.status()).isEqualTo(TodoStatus.PENDING);
    assertThat(todo.sortOrder()).isZero();
    assertThat(todo.createdBy()).isEqualTo(UUID.fromString(user.id()));
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.CREATE
        && r.entityType() == AuditEntityType.WORK_ORDER_TODO
        && r.entityLabel().equals("Fix bearing")));
  }

  @Test
  @DisplayName("10.7-SVC-002 P0 todo create on a terminal workorder is 400 WORKORDER_TERMINAL")
  void createTodoTerminalWorkorder() {
    var user = assignedTechnician();
    var entity = entity(technicianId, WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID, new CreateTodoCommand("Fix", null, null)))
        .isInstanceOf(WorkOrderTerminalException.class);
    verify(todos, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("10.7-SVC-003 P0 todo create with blank title fails validation")
  void createTodoBlankTitle() {
    var user = assignedTechnician();
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID, new CreateTodoCommand("", null, null)))
        .isInstanceOfSatisfying(WorkOrderTodoValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("title"));
  }

  @Test
  @DisplayName("10.7-SVC-004 P0 todo create with title over 200 chars fails validation")
  void createTodoTitleTooLong() {
    var user = assignedTechnician();
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID, new CreateTodoCommand("x".repeat(201), null, null)))
        .isInstanceOfSatisfying(WorkOrderTodoValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("title"));
  }

  @Test
  @DisplayName("10.7-SVC-005 P0 todo create by a non-executor non-leader is forbidden")
  void createTodoForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "audit@syncro.dev", ApplicationRole.AUDITOR);
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID, new CreateTodoCommand("Fix", null, null)))
        .isInstanceOf(TodoForbiddenException.class);
  }

  @Test
  @DisplayName("10.7-SVC-006 P0 todo create on an unknown workorder is 404")
  void createTodoWorkOrderNotFound() {
    var user = assignedTechnician();
    when(workOrders.findById("WO-2409-NADA")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(user, "WO-2409-NADA", new CreateTodoCommand("Fix", null, null)))
        .isInstanceOf(TodoWorkOrderNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // List todos
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.7-SVC-007 P0 list returns todos ordered by sortOrder for any authenticated user")
  void listTodosOk() {
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var todo1 = todoEntity(UUID.randomUUID(), "B", 1);
    var todo2 = todoEntity(UUID.randomUUID(), "A", 0);
    when(todos.findByWorkorderIdOrderBySortOrderAsc(WORKORDER_ID)).thenReturn(List.of(todo2, todo1));

    var result = service.list(WORKORDER_ID);

    assertThat(result).hasSize(2);
    assertThat(result.getFirst().title()).isEqualTo("A");
    assertThat(result.get(1).title()).isEqualTo("B");
  }

  @Test
  @DisplayName("10.7-SVC-008 P0 list on an unknown workorder is 404")
  void listTodosWorkOrderNotFound() {
    when(workOrders.findById("WO-2409-NADA")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.list("WO-2409-NADA"))
        .isInstanceOf(TodoWorkOrderNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // Assign todo
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.7-SVC-009 P0 executor/leader assigns a technician and audits UPDATE")
  void assignTodoOk() {
    var user = assignedTechnician();
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    var todo = todoEntity(UUID.randomUUID(), "Fix bearing", 0);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(todos.findById(todo.getId())).thenReturn(Optional.of(todo));
    when(todos.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var newTechId = UUID.randomUUID();
    when(users.findById(newTechId)).thenReturn(Optional.of(mockUser()));

    var result = service.assign(user, WORKORDER_ID, todo.getId(), newTechId);

    assertThat(result.assignedTechnicianId()).isEqualTo(newTechId);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.entityType() == AuditEntityType.WORK_ORDER_TODO));
  }

  @Test
  @DisplayName("10.7-SVC-009a P0 assign to a non-existent user is rejected")
  void assignTodoTechnicianNotFound() {
    var user = assignedTechnician();
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    var todo = todoEntity(UUID.randomUUID(), "Fix bearing", 0);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(todos.findById(todo.getId())).thenReturn(Optional.of(todo));
    var ghostId = UUID.randomUUID();
    when(users.findById(ghostId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.assign(user, WORKORDER_ID, todo.getId(), ghostId))
        .isInstanceOf(WorkOrderTodoService.TodoTechnicianNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // Complete todo
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.7-SVC-010 P0 executor/leader marks a todo complete and audits UPDATE")
  void completeTodoOk() {
    var user = assignedTechnician();
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    var todo = todoEntity(UUID.randomUUID(), "Fix bearing", 0);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(todos.findById(todo.getId())).thenReturn(Optional.of(todo));
    when(todos.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.complete(user, WORKORDER_ID, todo.getId());

    assertThat(result.status()).isEqualTo(TodoStatus.COMPLETED);
    assertThat(result.completedAt()).isEqualTo(NOW);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.entityType() == AuditEntityType.WORK_ORDER_TODO));
  }

  @Test
  @DisplayName("10.7-SVC-011 P0 completing an already-completed todo is rejected")
  void completeTodoAlreadyDone() {
    var user = assignedTechnician();
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    var todo = todoEntity(UUID.randomUUID(), "Fix bearing", 0);
    todo.complete(NOW);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(todos.findById(todo.getId())).thenReturn(Optional.of(todo));

    assertThatThrownBy(() -> service.complete(user, WORKORDER_ID, todo.getId()))
        .isInstanceOf(TodoAlreadyCompletedException.class);
  }

  @Test
  @DisplayName("10.7-SVC-011a P0 the todo's assigned technician (not workorder executor) may complete it")
  void completeTodoByTodoAssignee() {
    var todoAssigneeId = UUID.randomUUID();
    var user = new AuthenticatedUser(todoAssigneeId.toString(), "todo-assignee@syncro.dev", ApplicationRole.TECHNICIAN);
    // Workorder executor is someone else — the todo assignee is not the workorder executor.
    var entity = entity(UUID.randomUUID(), WorkOrderStatus.IN_PROGRESS);
    var todo = todoEntity(UUID.randomUUID(), "Fix bearing", 0);
    todo.assign(todoAssigneeId, NOW);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(todos.findById(todo.getId())).thenReturn(Optional.of(todo));
    when(todos.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.complete(user, WORKORDER_ID, todo.getId());

    assertThat(result.status()).isEqualTo(TodoStatus.COMPLETED);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.entityType() == AuditEntityType.WORK_ORDER_TODO));
  }

  @Test
  @DisplayName("10.7-SVC-011b P0 completing a CANCELLED todo is rejected")
  void completeTodoCancelled() {
    var user = assignedTechnician();
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    var todo = todoEntity(UUID.randomUUID(), "Fix bearing", 0);
    todo.cancel(NOW);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(todos.findById(todo.getId())).thenReturn(Optional.of(todo));

    assertThatThrownBy(() -> service.complete(user, WORKORDER_ID, todo.getId()))
        .isInstanceOf(TodoAlreadyCompletedException.class);
  }

  @Test
  @DisplayName("10.7-SVC-011c P0 a non-executor non-leader non-assignee cannot complete a todo")
  void completeTodoForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "audit@syncro.dev", ApplicationRole.AUDITOR);
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    var todo = todoEntity(UUID.randomUUID(), "Fix bearing", 0);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(todos.findById(todo.getId())).thenReturn(Optional.of(todo));

    assertThatThrownBy(() -> service.complete(user, WORKORDER_ID, todo.getId()))
        .isInstanceOf(TodoForbiddenException.class);
  }

  // -------------------------------------------------------------------------
  // Reorder todo
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.7-SVC-012 P0 executor/leader reorders a todo and audits UPDATE")
  void reorderTodoOk() {
    var user = assignedTechnician();
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    var todo = todoEntity(UUID.randomUUID(), "Fix bearing", 0);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(todos.findById(todo.getId())).thenReturn(Optional.of(todo));
    when(todos.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.reorder(user, WORKORDER_ID, todo.getId(), 5);

    assertThat(result.sortOrder()).isEqualTo(5);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.entityType() == AuditEntityType.WORK_ORDER_TODO));
  }

  // -------------------------------------------------------------------------
  // Delete todo
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.7-SVC-013 P0 executor/leader deletes a todo and audits DELETE")
  void deleteTodoOk() {
    var user = assignedTechnician();
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    var todo = todoEntity(UUID.randomUUID(), "Fix bearing", 0);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(todos.findById(todo.getId())).thenReturn(Optional.of(todo));

    service.delete(user, WORKORDER_ID, todo.getId());

    verify(todos).delete(todo);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.DELETE
        && r.entityType() == AuditEntityType.WORK_ORDER_TODO));
  }

  @Test
  @DisplayName("10.7-SVC-014 P0 delete on an unknown todo is 404 TODO_NOT_FOUND")
  void deleteTodoNotFound() {
    var user = assignedTechnician();
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    var todoId = UUID.randomUUID();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(todos.findById(todoId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete(user, WORKORDER_ID, todoId))
        .isInstanceOf(TodoNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // Kanban
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.7-SVC-015 P0 kanban returns scope-filtered workorders grouped by status with embedded todos")
  void kanbanOk() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "tech@syncro.dev", ApplicationRole.TECHNICIAN);
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    var category = new com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity(
        UUID.randomUUID(), "01", "Breakdown", null, NOW, NOW);
    var todo = todoEntity(UUID.randomUUID(), "Fix bearing", 0);
    var row = new WorkOrderKanbanRow(entity, category, todo);
    when(workOrders.findKanbanRows(any(), eq(false), any(), any())).thenReturn(List.of(row));

    var view = service.kanban(user);

    assertThat(view.groups()).containsKey(WorkOrderStatus.IN_PROGRESS);
    var items = view.groups().get(WorkOrderStatus.IN_PROGRESS);
    assertThat(items).hasSize(1);
    assertThat(items.getFirst().categoryCode()).isEqualTo("01");
    assertThat(items.getFirst().todos()).hasSize(1);
    assertThat(items.getFirst().todos().getFirst().title()).isEqualTo("Fix bearing");
    // Every non-terminal status key is present (even if empty).
    assertThat(view.groups().get(WorkOrderStatus.OPEN)).isEmpty();
    assertThat(view.groups().get(WorkOrderStatus.PENDING_SPAREPART)).isEmpty();
    assertThat(view.groups().get(WorkOrderStatus.PENDING_REVIEW)).isEmpty();
  }

  @Test
  @DisplayName("10.7-SVC-016 P0 kanban for a user with no scope returns empty groups")
  void kanbanEmptyScope() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "audit@syncro.dev", ApplicationRole.AUDITOR);
    var scope = new OperationalScope(Set.of(), Set.of(), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    when(workOrders.findKanbanRows(any(), eq(false), any(), any())).thenReturn(List.of());

    var view = service.kanban(user);

    assertThat(view.groups().get(WorkOrderStatus.OPEN)).isEmpty();
    assertThat(view.groups().get(WorkOrderStatus.IN_PROGRESS)).isEmpty();
    assertThat(view.groups().get(WorkOrderStatus.PENDING_SPAREPART)).isEmpty();
    assertThat(view.groups().get(WorkOrderStatus.PENDING_REVIEW)).isEmpty();
  }

  @Test
  @DisplayName("10.7-SVC-017 P0 kanban for SUPER_ADMIN returns all non-terminal workorders (unrestricted)")
  void kanbanSuperAdmin() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
    // SUPER_ADMIN scope: plantIds is null, which means unrestricted.
    var scope = new OperationalScope(null, Set.of(), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    var entity = entity(technicianId, WorkOrderStatus.OPEN);
    var row = new WorkOrderKanbanRow(entity, null, null);
    when(workOrders.findKanbanRows(any(), eq(true), any(), any())).thenReturn(List.of(row));

    var view = service.kanban(user);

    assertThat(view.groups().get(WorkOrderStatus.OPEN)).hasSize(1);
    assertThat(view.groups().get(WorkOrderStatus.OPEN).getFirst().todos()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private WorkOrderEntity entity(UUID assignedTechnician, WorkOrderStatus status) {
    return new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, status, UUID.randomUUID(),
        machineId, "desc", 0, null, assignedTechnician, UUID.randomUUID(), NOW, NOW);
  }

  private WorkOrderTodoEntity todoEntity(UUID id, String title, int sortOrder) {
    return new WorkOrderTodoEntity(id, WORKORDER_ID, title, null, null, TodoStatus.PENDING, sortOrder,
        UUID.randomUUID(), NOW, NOW, null);
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

  private static AuthUserEntity mockUser() {
    return new AuthUserEntity(UUID.randomUUID(), "tech@syncro.dev", "hash", ApplicationRole.TECHNICIAN,
        true, NOW, NOW);
  }
}