package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.domain.workorder.TodoStatus;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.domain.workorder.WorkOrderTodo;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderKanbanRow;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderTodoEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderTodoRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workorder todos & kanban (FR-119, story 10-7). Each todo is a local operational field
 * (AD-3 "preserved") — allowed on both EXTERNAL and INTERNAL workorders, never touches
 * status or sync_version. Mutations are gated on the same executor/leader access as
 * 10.4/10.5 (in-scope leader OR assigned executor) and blocked on terminal workorders
 * (CLOSED/CANCELLED). Reads (list todos, kanban) are any-authenticated — the
 * workorder read posture.
 */
@Service
public class WorkOrderTodoService {

  private static final List<WorkOrderStatus> TERMINAL_STATUSES = List.of(
      WorkOrderStatus.CLOSED, WorkOrderStatus.CANCELLED);

  /** Non-terminal statuses the kanban board groups by (design note: open workorders). */
  private static final List<WorkOrderStatus> KANBAN_STATUSES = List.of(
      WorkOrderStatus.OPEN, WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.PENDING_SPAREPART,
      WorkOrderStatus.PENDING_REVIEW);

  private final WorkOrderRepository workOrders;
  private final WorkOrderTodoRepository todos;
  private final MachineRepository machines;
  private final AuthUserRepository users;
  private final AuditLogWriter auditLog;
  private final OperationalScopeService scopes;
  private final Clock clock;

  public WorkOrderTodoService(WorkOrderRepository workOrders, WorkOrderTodoRepository todos,
      MachineRepository machines, AuthUserRepository users, AuditLogWriter auditLog,
      OperationalScopeService scopes, Clock clock) {
    this.workOrders = workOrders;
    this.todos = todos;
    this.machines = machines;
    this.users = users;
    this.auditLog = auditLog;
    this.scopes = scopes;
    this.clock = clock;
  }

  /** Creates a todo on a non-terminal workorder (gate: executor/leader) */
  @Transactional
  public WorkOrderTodo create(AuthenticatedUser user, String workOrderId, CreateTodoCommand command) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireAccess(user, entity, machine);
    requireNonTerminal(entity);
    validateTitle(command.title());

    var now = Instant.now(clock);
    var nextSortOrder = nextSortOrder(workOrderId);
    var todo = new WorkOrderTodoEntity(UUID.randomUUID(), workOrderId, command.title().trim(),
        normalize(command.description()), command.assignedTechnicianId(), TodoStatus.PENDING, nextSortOrder,
        UUID.fromString(user.id()), now, now, null);
    var saved = todos.saveAndFlush(todo);

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.WORK_ORDER_TODO,
        saved.getId(), saved.getTitle(), machine.getPlant().getId(), null, todoValues(saved), null));
    return WorkOrderMapper.toDomain(saved);
  }

  /** Lists the workorder's todos ordered by sortOrder asc (any authenticated user) */
  @Transactional(readOnly = true)
  public List<WorkOrderTodo> list(String workOrderId) {
    loadWorkOrder(workOrderId);
    return todos.findByWorkorderIdOrderBySortOrderAsc(workOrderId).stream()
        .map(WorkOrderMapper::toDomain)
        .toList();
  }

  /** Assigns (or changes) the todo's technician (gate: executor/leader) */
  @Transactional
  public WorkOrderTodo assign(AuthenticatedUser user, String workOrderId, UUID todoId, UUID technicianId) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireAccess(user, entity, machine);
    requireNonTerminal(entity);

    var todo = loadTodo(todoId, workOrderId);
    if (todo.getStatus() == TodoStatus.COMPLETED || todo.getStatus() == TodoStatus.CANCELLED) {
      throw new TodoAlreadyCompletedException();
    }
    requireTechnicianExists(technicianId);
    var previous = todoValues(todo);
    todo.assign(technicianId, Instant.now(clock));
    var saved = todos.saveAndFlush(todo);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ORDER_TODO,
        saved.getId(), saved.getTitle(), machine.getPlant().getId(), previous, todoValues(saved), null));
    return WorkOrderMapper.toDomain(saved);
  }

  /** Marks a todo complete (gate: executor/leader OR the todo's assigned technician) */
  @Transactional
  public WorkOrderTodo complete(AuthenticatedUser user, String workOrderId, UUID todoId) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireNonTerminal(entity);

    var todo = loadTodo(todoId, workOrderId);
    if (todo.getStatus() == TodoStatus.COMPLETED || todo.getStatus() == TodoStatus.CANCELLED) {
      throw new TodoAlreadyCompletedException();
    }
    // The assigned technician may mark their own todo complete even without workorder executor access.
    if (!isTodoAssignedTechnician(user, todo) && !isInScopeLeader(user, machine) && !isExecutor(user, entity)) {
      throw new TodoForbiddenException();
    }
    var previous = todoValues(todo);
    todo.complete(Instant.now(clock));
    var saved = todos.saveAndFlush(todo);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ORDER_TODO,
        saved.getId(), saved.getTitle(), machine.getPlant().getId(), previous, todoValues(saved), null));
    return WorkOrderMapper.toDomain(saved);
  }

  /** Reorders a todo within its workorder (gate: executor/leader) */
  @Transactional
  public WorkOrderTodo reorder(AuthenticatedUser user, String workOrderId, UUID todoId, int sortOrder) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireAccess(user, entity, machine);
    requireNonTerminal(entity);

    var todo = loadTodo(todoId, workOrderId);
    var previous = todoValues(todo);
    todo.reorder(sortOrder, Instant.now(clock));
    var saved = todos.saveAndFlush(todo);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ORDER_TODO,
        saved.getId(), saved.getTitle(), machine.getPlant().getId(), previous, todoValues(saved), null));
    return WorkOrderMapper.toDomain(saved);
  }

  /** Deletes a todo (gate: executor/leader) */
  @Transactional
  public void delete(AuthenticatedUser user, String workOrderId, UUID todoId) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireAccess(user, entity, machine);
    requireNonTerminal(entity);

    var todo = loadTodo(todoId, workOrderId);
    todos.delete(todo);

    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.WORK_ORDER_TODO,
        todoId, todo.getTitle(), machine.getPlant().getId(), todoValues(todo), null, null));
  }

  /**
     Kanban read (GET /kanban, any authenticated user): scope-filtered workorders grouped
     by status with todos embedded. Single joined query (no N+1), grouped in application
     code. SUPER_ADMIN is unrestricted (derived scope plantIds is null); every other user
     sees only workorders whose machine plant OR machine group is in their derived scope.
    */
  @Transactional(readOnly = true)
  public KanbanView kanban(AuthenticatedUser user) {
    var scope = scopes.derive(user);
    var unrestricted = scope.plantIds() == null;
    var groupIds = new java.util.HashSet<UUID>();
    groupIds.addAll(scope.machineGroupIds());
    groupIds.addAll(scope.activeTeamIds());

    var rows = workOrders.findKanbanRows(
        TERMINAL_STATUSES, unrestricted,
        scope.plantIds() == null ? List.of() : scope.plantIds(), groupIds);
    return groupKanban(rows);
  }

  /** Groups the flat query rows into status → workorder items (rows are contiguous per workorder) */
  private KanbanView groupKanban(List<WorkOrderKanbanRow> rows) {
    var itemsByWorkorderId = new LinkedHashMap<String, WorkOrderKanbanItemBuilder>();
    for (var row : rows) {
      var workOrder = row.workOrder();
      var builder = itemsByWorkorderId.computeIfAbsent(workOrder.getId(),
          id -> new WorkOrderKanbanItemBuilder(workOrder, row.category()));
      if (row.todo() != null) {
        builder.todos.add(row.todo());
      }
    }
    var byStatus = new EnumMap<WorkOrderStatus, List<WorkOrderKanbanItem>>(WorkOrderStatus.class);
    for (var status : KANBAN_STATUSES) {
      byStatus.put(status, new ArrayList<>());
    }
    for (var builder : itemsByWorkorderId.values()) {
      byStatus.get(builder.workOrder.getStatus()).add(builder.build());
    }
    return new KanbanView(byStatus);
  }

  // -------------------------------------------------------------------------
  // Validation & gates
  // -------------------------------------------------------------------------

  private void validateTitle(String title) {
    var fieldErrors = new LinkedHashMap<String, String>();
    var normalized = title == null ? "" : title.trim();
    if (normalized.isEmpty()) {
      fieldErrors.put("title", "Title must not be blank.");
    } else if (normalized.length() > 200) {
      fieldErrors.put("title", "Title must be at most 200 characters.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new WorkOrderTodoValidationException(fieldErrors);
    }
  }

  /** Blank → null so a cleared field persists as NULL, not an empty string */
  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private WorkOrderEntity loadWorkOrder(String workOrderId) {
    return workOrders.findById(workOrderId).orElseThrow(TodoWorkOrderNotFoundException::new);
  }

  private MachineEntity loadMachine(WorkOrderEntity workOrder) {
    return machines.findByIdWithPlantAndGroup(workOrder.getMachineId())
        .orElseThrow(TodoWorkOrderMachineNotFoundException::new);
  }

  private WorkOrderTodoEntity loadTodo(UUID todoId, String workOrderId) {
    return todos.findById(todoId)
        .filter(todo -> todo.getWorkorderId().equals(workOrderId))
        .orElseThrow(TodoNotFoundException::new);
  }

  private int nextSortOrder(String workOrderId) {
    var existing = todos.findByWorkorderIdOrderBySortOrderAsc(workOrderId);
    return existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getSortOrder() + 1;
  }
  // ponytail: sort_order is advisory display order (no UNIQUE constraint) — concurrent
  // creates may produce equal sort_order values; acceptable for a kanban card, add a
  // PESSIMISTIC_WRITE query if ordering becomes correctness-critical.

  /**
     Terminal-state guard (FR-119): todos cannot be mutated on a DONE/CLOSED/CANCELLED
     workorder — same rationale as the status lifecycle terminal states.
    */
  private void requireNonTerminal(WorkOrderEntity entity) {
    if (TERMINAL_STATUSES.contains(entity.getStatus())) {
      throw new WorkOrderTerminalException();
    }
  }

  /** Assigning to a ghost user would create an invisible orphan assignment (FR-119) */
  private void requireTechnicianExists(UUID technicianId) {
    if (!users.findById(technicianId).isPresent()) {
      throw new TodoTechnicianNotFoundException();
    }
  }

  /**
     Same gate as {@code WorkOrderService.requireSessionAccess} (10.4) and the
     evidence/report gates (10.5/10.6): in-scope leader OR assigned executor, both
     sources allowed — todos are local operational fields. Duplicated inline — the
     existing gates stay untouched.
    */
  private void requireAccess(AuthenticatedUser user, WorkOrderEntity entity, MachineEntity machine) {
    if (isInScopeLeader(user, machine) || isExecutor(user, entity)) {
      return;
    }
    throw new TodoForbiddenException();
  }

  private boolean isExecutor(AuthenticatedUser user, WorkOrderEntity entity) {
    return entity.getAssignedTechnicianId() != null
        && UUID.fromString(user.id()).equals(entity.getAssignedTechnicianId())
        && (user.applicationRole() == ApplicationRole.TECHNICIAN
            || user.applicationRole() == ApplicationRole.STAFF_MAINTENANCE);
  }

  private boolean isInScopeLeader(AuthenticatedUser user, MachineEntity machine) {
    switch (user.applicationRole()) {
      case SUPER_ADMIN -> {
        return true;
      }
      case SECTION_LEADER -> {
        return groupInScope(scopes.derive(user), machine);
      }
      case MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {
        var scope = scopes.derive(user);
        var plantInScope = scope.plantIds() != null && scope.plantIds().contains(machine.getPlant().getId());
        return groupInScope(scope, machine) || plantInScope;
      }
      default -> {
        return false;
      }
    }
  }

  private boolean groupInScope(OperationalScope scope, MachineEntity machine) {
    return scope.machineGroupIds().contains(machine.getMachineGroup().getId())
        || scope.activeTeamIds().contains(machine.getMachineGroup().getId());
  }

  private boolean isTodoAssignedTechnician(AuthenticatedUser user, WorkOrderTodoEntity todo) {
    return todo.getAssignedTechnicianId() != null
        && UUID.fromString(user.id()).equals(todo.getAssignedTechnicianId());
  }

  private Map<String, Object> todoValues(WorkOrderTodoEntity todo) {
    var values = new HashMap<String, Object>();
    values.put("id", todo.getId());
    values.put("workorderId", todo.getWorkorderId());
    values.put("title", todo.getTitle());
    values.put("description", todo.getDescription());
    values.put("assignedTechnicianId", todo.getAssignedTechnicianId());
    values.put("status", todo.getStatus().name());
    values.put("sortOrder", todo.getSortOrder());
    values.put("completedAt", todo.getCompletedAt());
    return values;
  }

  // -------------------------------------------------------------------------
  // Kanban grouping helpers
  // -------------------------------------------------------------------------

  /** Mutable builder for one workorder's kanban item; rows are contiguous per workorder */
  private static final class WorkOrderKanbanItemBuilder {
    private final WorkOrderEntity workOrder;
    private final String categoryCode;
    private final List<WorkOrderTodoEntity> todos = new ArrayList<>();

    private WorkOrderKanbanItemBuilder(WorkOrderEntity workOrder, WorkOrderCategoryEntity category) {
      this.workOrder = workOrder;
      this.categoryCode = category != null ? category.getCode() : null;
    }

    private WorkOrderKanbanItem build() {
      var todoViews = todos.stream().map(WorkOrderMapper::toDomain).toList();
      return new WorkOrderKanbanItem(workOrder.getId(), workOrder.getStatus(), categoryCode,
          workOrder.getMachineId(), workOrder.getDescription(), workOrder.getAssignedTechnicianId(),
          workOrder.getCreatedAt(), todoViews);
    }
  }

  // -------------------------------------------------------------------------
  // Commands & views
  // -------------------------------------------------------------------------

  public record CreateTodoCommand(String title, String description, UUID assignedTechnicianId) {
  }

  /** One kanban item: a workorder with its embedded todos (FR-119) */
  public record WorkOrderKanbanItem(String id, WorkOrderStatus status, String categoryCode, UUID machineId,
      String description, UUID assignedTechnicianId, Instant createdAt, List<WorkOrderTodo> todos) {
  }

  /** Kanban view: status → workorder items; every non-terminal status key is always present */
  public record KanbanView(Map<WorkOrderStatus, List<WorkOrderKanbanItem>> groups) {
  }

  public static class WorkOrderTodoValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public WorkOrderTodoValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new HashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }

  public static class TodoForbiddenException extends RuntimeException {
  }

  public static class TodoWorkOrderNotFoundException extends RuntimeException {
  }

  public static class TodoWorkOrderMachineNotFoundException extends RuntimeException {
  }

  public static class TodoNotFoundException extends RuntimeException {
  }

  public static class TodoTechnicianNotFoundException extends RuntimeException {
  }

  /** A todo is terminal (COMPLETED or CANCELLED) — further mutations are rejected */
  public static class TodoAlreadyCompletedException extends RuntimeException {
  }

  /** Todos cannot be mutated on a DONE/CLOSED/CANCELLED workorder (FR-119) */
  public static class WorkOrderTerminalException extends RuntimeException {
  }
}