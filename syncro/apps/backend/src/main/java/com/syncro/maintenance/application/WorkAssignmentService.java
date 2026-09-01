package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.maintenance.domain.workorder.WorkAssignmentParentType;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkAssignmentEntity;
import com.syncro.maintenance.infrastructure.db.WorkAssignmentRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryRepository;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderUserNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderForbiddenException;
import com.syncro.maintenance.application.WorkOrderService.SelfAssignmentForbiddenException;
import com.syncro.maintenance.application.WorkOrderService.InvalidStateTransitionException;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.authz.application.PolicyDecisionPoint;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-technician work assignment service (blueprint B3, AD-17, story 17-1).
 *
 * <p>Assigns one or more technicians to a workorder via the {@code work_assignments}
 * table. The first assignment on an OPEN workorder triggers the OPEN → IN_PROGRESS
 * transition (matching the single-tech {@link WorkOrderService#assign} behavior);
 * subsequent assignments on an already-started workorder do not re-transition.
 * Drops are soft (is_active=false, dropped_at/dropped_by set). Every mutation
 * writes an audit_log row.
 *
 * <p>Role gate mirrors {@code WorkOrderService.requireAssignRole} (SUPER_ADMIN,
 * MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER) with the same scope
 * rules. SECTION_LEADER cannot assign themselves (FR-113). Assignees must hold
 * TECHNICIAN or STAFF_MAINTENANCE role. EXTERNAL workorders and terminal
 * (CLOSED/CANCELLED) workorders are rejected.
 */
@Service
public class WorkAssignmentService {

  static final String SOURCE_INTERNAL = "INTERNAL";
  static final String HISTORY_SOURCE_MANUAL = "MANUAL";

  private final WorkAssignmentRepository assignments;
  private final WorkOrderRepository workOrders;
  private final WorkOrderStatusHistoryRepository statusHistory;
  private final MachineRepository machines;
  private final AuthUserRepository users;
  private final OperationalScopeService scopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public WorkAssignmentService(WorkAssignmentRepository assignments, WorkOrderRepository workOrders,
      WorkOrderStatusHistoryRepository statusHistory, MachineRepository machines,
      AuthUserRepository users, OperationalScopeService scopes, AuditLogWriter auditLog, Clock clock) {
    this.assignments = assignments;
    this.workOrders = workOrders;
    this.statusHistory = statusHistory;
    this.machines = machines;
    this.users = users;
    this.scopes = scopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /**
   * Assigns a technician to a workorder. Creates a {@code work_assignments} row.
   * If the workorder is OPEN and this is the first active assignment, transitions
   * it to IN_PROGRESS (one status-history row + audit for the transition).
   *
   * @param user        the authenticated user performing the assignment
   * @param workOrderId the workorder id (VARCHAR PK)
   * @param command     the assignment details
   * @return a view of the created assignment
   */
  @Transactional
  public WorkAssignmentView assign(AuthenticatedUser user, String workOrderId, AssignWorkAssignmentCommand command) {
    requireAssignRole(user);
    // Pessimistic lock: two concurrent first assignments on the same OPEN workorder must
    // not both transition to IN_PROGRESS (mirrors WorkOrderService.transition).
    var entity = workOrders.findByIdForUpdate(workOrderId).orElseThrow(WorkOrderNotFoundException::new);
    if (!SOURCE_INTERNAL.equals(entity.getSource())) {
      // EXTERNAL workorders are owned by the sync module (FR-111).
      throw new WorkorderForbiddenException();
    }
    var status = entity.getStatus();
    if (status == WorkOrderStatus.CLOSED || status == WorkOrderStatus.CANCELLED) {
      throw new InvalidStateTransitionException();
    }
    var machine = machines.findByIdWithPlantAndGroup(entity.getMachineId())
        .orElseThrow(WorkOrderService.WorkOrderMachineNotFoundException::new);
    requireAssignScope(user, machine);

    var technicianId = command.technicianId();
    var assigneeEntity = users.findById(technicianId).orElseThrow(WorkOrderUserNotFoundException::new);
    if (assigneeEntity.getApplicationRole() != ApplicationRole.TECHNICIAN
        && assigneeEntity.getApplicationRole() != ApplicationRole.STAFF_MAINTENANCE) {
      throw new WorkorderForbiddenException();
    }
    // FR-113 parity: never assign to a deactivated/locked user.
    if (!assigneeEntity.isEnabled()) {
      throw new WorkorderForbiddenException();
    }
    if (user.applicationRole() == ApplicationRole.SECTION_LEADER
        && technicianId.equals(UUID.fromString(user.id()))) {
      throw new SelfAssignmentForbiddenException();
    }
    // Friendly active-duplicate pre-check: the unique key includes assigned_at, so the
    // same technician could otherwise be assigned twice at different timestamps.
    if (assignments.existsByWorkOrderIdAndTechnicianIdAndActiveTrue(workOrderId, technicianId)) {
      throw new AssignmentAlreadyExistsException();
    }

    var now = Instant.now(clock);
    var assignmentId = UUID.randomUUID();
    var assignment = new WorkAssignmentEntity(assignmentId, WorkAssignmentParentType.CORRECTIVE_WO,
        workOrderId, technicianId, UUID.fromString(user.id()), now, null, null, true, now, now);

    WorkOrderEntity saved = null;
    try {
      assignments.saveAndFlush(assignment);
      var previous = auditValues(entity);
      var transitioned = maybeTransitionToInProgress(entity, user, workOrderId, now);
      if (transitioned) {
        saved = workOrders.saveAndFlush(entity);
      }
      auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.WORK_ASSIGNMENT,
          assignmentId, workOrderId, machine.getPlant().getId(), null, assignmentAuditValues(assignment), null));
      if (saved != null) {
        // First assignment: log the OPEN → IN_PROGRESS transition as a workorder audit
        auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ORDER,
            auditEntityId(workOrderId), workOrderId, machine.getPlant().getId(), previous, auditValues(saved), null));
      }
    } catch (DataIntegrityViolationException exception) {
      if (isUniqueConstraintViolation(exception)) {
        throw new AssignmentAlreadyExistsException();
      }
      throw exception;
    }

    return toView(assignment);
  }

  /**
   * Drops (soft-deactivates) an active assignment. Sets {@code is_active=false}
   * and records {@code dropped_at/dropped_by}. The lead technician
   * ({@code work_orders.assigned_technician_id}) is unchanged.
   *
   * @param user         the authenticated user performing the drop
   * @param workOrderId  the workorder id
   * @param assignmentId the assignment id to drop
   * @return a view of the dropped assignment
   */
  @Transactional
  public WorkAssignmentView drop(AuthenticatedUser user, String workOrderId, UUID assignmentId) {
    requireAssignRole(user);
    // Lock the workorder row so two concurrent drops cannot race past the isActive check.
    var entity = workOrders.findByIdForUpdate(workOrderId).orElseThrow(WorkOrderNotFoundException::new);
    if (!SOURCE_INTERNAL.equals(entity.getSource())) {
      throw new WorkorderForbiddenException();
    }
    var machine = machines.findByIdWithPlantAndGroup(entity.getMachineId())
        .orElseThrow(WorkOrderService.WorkOrderMachineNotFoundException::new);
    requireAssignScope(user, machine);

    var assignment = assignments.findById(assignmentId)
        .orElseThrow(WorkAssignmentNotFoundException::new);
    if (!assignment.getWorkOrderId().equals(workOrderId)) {
      throw new WorkAssignmentNotFoundException();
    }

    var now = Instant.now(clock);
    var previous = assignmentAuditValues(assignment);
    // Atomic conditional update: WHERE is_active = true makes two concurrent drops
    // race-safe — exactly one wins, the loser updates 0 rows.
    var updated = assignments.deactivateIfActive(assignmentId, UUID.fromString(user.id()), now);
    if (updated == 0) {
      throw new AssignmentAlreadyDroppedException();
    }
    // The @Modifying query bypasses the persistence context — reload the row for the view.
    assignment = assignments.findById(assignmentId).orElseThrow(WorkAssignmentNotFoundException::new);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ASSIGNMENT,
        assignmentId, workOrderId, machine.getPlant().getId(), previous, assignmentAuditValues(assignment), null));
    return toView(assignment);
  }

  /**
   * Lists all assignments for a workorder, ordered by assignedAt ascending.
   * Any authenticated user may read.
   */
  @Transactional(readOnly = true)
  public List<WorkAssignmentView> list(String workOrderId) {
    if (!workOrders.existsById(workOrderId)) {
      throw new WorkOrderNotFoundException();
    }
    return assignments.findByWorkOrderIdOrderByAssignedAtAsc(workOrderId).stream()
        .map(this::toView)
        .toList();
  }

  // -------------------------------------------------------------------------
  // Gates & scope
  // -------------------------------------------------------------------------

  private void requireAssignRole(AuthenticatedUser user) {
    switch (user.applicationRole()) {
      case SUPER_ADMIN, MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER -> {
        // allowed
      }
      case STAFF_MAINTENANCE, TECHNICIAN, INVENTORY_MAINTENANCE, STOREKEEPER, PRODUCTION_LEADER, AUDITOR ->
          throw new WorkorderForbiddenException();
    }
  }

  private void requireAssignScope(AuthenticatedUser user, MachineEntity machine) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    var scope = scopes.derive(user);
    if (user.applicationRole() == ApplicationRole.SECTION_LEADER) {
      if (!groupInScope(scope, machine)) {
        throw new WorkorderForbiddenException();
      }
      return;
    }
    var plantInScope = scope.plantIds() != null && scope.plantIds().contains(machine.getPlant().getId());
    if (!groupInScope(scope, machine) && !plantInScope) {
      throw new WorkorderForbiddenException();
    }
  }

  private boolean groupInScope(OperationalScope scope, MachineEntity machine) {
    var groupIds = scope.machineGroupIds();
    var teamIds = scope.activeTeamIds();
    if (groupIds == null && teamIds == null) {
      return false;
    }
    var machineGroupId = machine.getMachineGroup().getId();
    return (groupIds != null && groupIds.contains(machineGroupId))
        || (teamIds != null && teamIds.contains(machineGroupId));
  }

  // -------------------------------------------------------------------------
  // Transition logic
  // -------------------------------------------------------------------------

  /**
   * Transitions the workorder from OPEN to IN_PROGRESS when it is OPEN (first active
   * assignment). Mutates the entity in place and writes the OPEN → IN_PROGRESS
   * status-history row; the caller persists the entity so it can also audit the
   * transition. Returns {@code true} when a transition was applied.
   */
  private boolean maybeTransitionToInProgress(WorkOrderEntity entity, AuthenticatedUser user,
      String workOrderId, Instant now) {
    if (entity.getStatus() != WorkOrderStatus.OPEN) {
      return false;
    }
    entity.transitionTo(WorkOrderStatus.IN_PROGRESS, now);
    statusHistory.saveAndFlush(historyRow(workOrderId, WorkOrderStatus.OPEN, WorkOrderStatus.IN_PROGRESS,
        user, now));
    return true;
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private boolean isUniqueConstraintViolation(DataIntegrityViolationException exception) {
    var cause = exception.getCause();
    while (cause != null) {
      if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
          && "uq_work_assignments_wo_tech_at".equalsIgnoreCase(constraint.getConstraintName())) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private WorkOrderStatusHistoryEntity historyRow(String workOrderId, WorkOrderStatus from, WorkOrderStatus to,
      AuthenticatedUser user, Instant transitionedAt) {
    return new WorkOrderStatusHistoryEntity(UUID.randomUUID(), workOrderId,
        from != null ? from.name() : null, to.name(), HISTORY_SOURCE_MANUAL, user.id(), traceId(), transitionedAt);
  }

  private String traceId() {
    return PolicyDecisionPoint.currentTraceId();
  }

  private UUID auditEntityId(String workOrderId) {
    return UUID.nameUUIDFromBytes(workOrderId.getBytes(StandardCharsets.UTF_8));
  }

  private Map<String, Object> auditValues(WorkOrderEntity entity) {
    var values = new HashMap<String, Object>();
    values.put("id", entity.getId());
    values.put("source", entity.getSource());
    values.put("status", entity.getStatus().name());
    values.put("categoryId", entity.getCategoryId());
    values.put("machineId", entity.getMachineId());
    values.put("parentId", entity.getParentId());
    values.put("description", entity.getDescription());
    values.put("assignedTechnicianId", entity.getAssignedTechnicianId());
    return values;
  }

  private Map<String, Object> assignmentAuditValues(WorkAssignmentEntity entity) {
    var values = new HashMap<String, Object>();
    values.put("id", entity.getId());
    values.put("workOrderId", entity.getWorkOrderId());
    values.put("technicianId", entity.getTechnicianId());
    values.put("assignedBy", entity.getAssignedBy());
    values.put("assignedAt", entity.getAssignedAt() != null ? entity.getAssignedAt().toString() : null);
    values.put("droppedAt", entity.getDroppedAt() != null ? entity.getDroppedAt().toString() : null);
    values.put("droppedBy", entity.getDroppedBy());
    values.put("isActive", entity.isActive());
    return values;
  }

  private WorkAssignmentView toView(WorkAssignmentEntity entity) {
    return new WorkAssignmentView(
        entity.getId(),
        entity.getWorkOrderId(),
        entity.getTechnicianId(),
        entity.getAssignedBy(),
        entity.getAssignedAt(),
        entity.getDroppedAt(),
        entity.getDroppedBy(),
        entity.isActive());
  }

  // -------------------------------------------------------------------------
  // DTOs
  // -------------------------------------------------------------------------

  public record AssignWorkAssignmentCommand(UUID technicianId) {
  }

  public record WorkAssignmentView(
      UUID id,
      String workOrderId,
      UUID technicianId,
      UUID assignedBy,
      Instant assignedAt,
      Instant droppedAt,
      UUID droppedBy,
      boolean isActive) {
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  public static class AssignmentAlreadyExistsException extends RuntimeException {
  }

  public static class AssignmentAlreadyDroppedException extends RuntimeException {
  }

  public static class WorkAssignmentNotFoundException extends RuntimeException {
  }
}
