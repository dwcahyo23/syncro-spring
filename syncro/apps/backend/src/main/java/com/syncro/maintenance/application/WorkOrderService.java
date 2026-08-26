package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.authz.application.PolicyDecisionPoint;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.domain.workorder.WorkOrder;
import com.syncro.maintenance.domain.workorder.WorkOrderIdGenerator;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workorder create & assign (FR-110/FR-113, story 10.2).
 *
 * <p>Create is gated per role: SUPER_ADMIN unrestricted; the plant-scoped roles
 * (MANAGER_MAINTENANCE/MAINTENANCE_LEADER/STAFF_MAINTENANCE) need plant access on the
 * target machine; SECTION_LEADER needs the machine's group inside their own scope;
 * PRODUCTION_LEADER may only open the "01" Breakdown category on their own plants
 * (AD-15 — line-level binding is a later story). A keyed replay within 5 minutes
 * returns the existing workorder instead of creating a duplicate (AD-3). Children
 * require the same scope on the parent's machine group.
 *
 * <p>Assign is gated to the four leadership roles plus a scope check on the workorder's
 * machine (group-in-scope OR plant scope), requires OPEN status, rejects SECTION_LEADER
 * self-assignment (FR-113), and stores the executing technician. Both paths write
 * status-history + audit rows. The in-service gates mirror the OPA
 * {@code workorder_mutation_paths} set (9-5 parity pattern — gate is authoritative).
 */
@Service
public class WorkOrderService {

  /** Category code constant for breakdown workorders (FR-110); codes are stored uppercase. */
  static final String BREAKDOWN_CATEGORY_CODE = "01";
  static final String SOURCE_INTERNAL = "INTERNAL";
  static final String HISTORY_SOURCE_MANUAL = "MANUAL";
  static final Duration IDEMPOTENCY_WINDOW = Duration.ofMinutes(5);

  private final WorkOrderIdGenerator idGenerator;
  private final WorkOrderRepository workOrders;
  private final WorkOrderStatusHistoryRepository statusHistory;
  private final WorkOrderCategoryRepository categories;
  private final MachineRepository machines;
  private final AuthUserRepository users;
  private final OperationalScopeService scopes;
  private final PlantScopeService plantScopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public WorkOrderService(WorkOrderIdGenerator idGenerator, WorkOrderRepository workOrders,
      WorkOrderStatusHistoryRepository statusHistory, WorkOrderCategoryRepository categories,
      MachineRepository machines, AuthUserRepository users, OperationalScopeService scopes,
      PlantScopeService plantScopes, AuditLogWriter auditLog, Clock clock) {
    this.idGenerator = idGenerator;
    this.workOrders = workOrders;
    this.statusHistory = statusHistory;
    this.categories = categories;
    this.machines = machines;
    this.users = users;
    this.scopes = scopes;
    this.plantScopes = plantScopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional
  public CreateResult create(AuthenticatedUser user, CreateWorkOrderCommand command) {
    requireCreateRole(user);
    var now = Instant.now(clock);
    var key = normalizedKey(command.idempotencyKey());
    var userId = UUID.fromString(user.id());

    var replayed = replayIfIdempotent(key, userId, now);
    if (replayed.isPresent()) {
      return new CreateResult(WorkOrderMapper.toDomain(replayed.get()), true);
    }

    var category = categories.findByCode(normalizeCategoryCode(command.categoryCode()))
        .orElseThrow(WorkOrderCategoryNotFoundException::new);
    var machine = machines.findByIdWithPlantAndGroup(command.machineId())
        .orElseThrow(WorkOrderMachineNotFoundException::new);
    requireCreateAccess(user, machine, category);

    var parentId = command.parentId();
    if (parentId != null) {
      var parent = workOrders.findById(parentId).orElseThrow(WorkOrderParentNotFoundException::new);
      var parentMachine = machines.findByIdWithPlantAndGroup(parent.getMachineId())
          .orElseThrow(WorkOrderMachineNotFoundException::new);
      requireCreateAccess(user, parentMachine, category);
    }

    var id = idGenerator.nextId();
    var entity = new WorkOrderEntity(id, SOURCE_INTERNAL, parentId, WorkOrderStatus.OPEN, category.getId(),
        command.machineId(), command.description(), 0L, key, null, userId, now, now);
    try {
      var saved = workOrders.saveAndFlush(entity);
      statusHistory.saveAndFlush(historyRow(id, null, WorkOrderStatus.OPEN, user, now));
      auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.WORK_ORDER,
          auditEntityId(id), id, machine.getPlant().getId(), null, auditValues(saved), null));
      return new CreateResult(WorkOrderMapper.toDomain(saved), false);
    } catch (DataIntegrityViolationException exception) {
      if (key == null || !isIdempotencyKeyViolation(exception)) {
        throw exception;
      }
      // A concurrent request won the race on the same idempotency key: return the winner.
      var winner = replayIfIdempotent(key, userId, now)
          .orElseThrow(() -> exception);
      return new CreateResult(WorkOrderMapper.toDomain(winner), true);
    }
  }

  @Transactional
  public WorkOrder assign(AuthenticatedUser user, String workOrderId, AssignWorkOrderCommand command) {
    requireAssignRole(user);
    var entity = workOrders.findById(workOrderId).orElseThrow(WorkOrderNotFoundException::new);
    if (!SOURCE_INTERNAL.equals(entity.getSource())) {
      // SYNCED workorders are owned by the sync module (FR-111); manual assignment would
      // diverge from the external sheet's lifecycle without a sync_version bump.
      throw new WorkorderForbiddenException();
    }
    var machine = machines.findByIdWithPlantAndGroup(entity.getMachineId())
        .orElseThrow(WorkOrderMachineNotFoundException::new);
    requireAssignScope(user, machine);

    if (entity.getStatus() != WorkOrderStatus.OPEN) {
      throw new InvalidStateTransitionException();
    }
    var assignee = command.assigneeUserId();
    var assigneeEntity = users.findById(assignee).orElseThrow(WorkOrderUserNotFoundException::new);
    if (assigneeEntity.getApplicationRole() != ApplicationRole.TECHNICIAN
        && assigneeEntity.getApplicationRole() != ApplicationRole.STAFF_MAINTENANCE) {
      // FR-113: assignment targets an executing technician; STAFF_MAINTENANCE may execute.
      throw new WorkorderForbiddenException();
    }
    if (user.applicationRole() == ApplicationRole.SECTION_LEADER
        && assignee.equals(UUID.fromString(user.id()))) {
      throw new SelfAssignmentForbiddenException();
    }

    var previous = auditValues(entity);
    var now = Instant.now(clock);
    entity.assign(assignee, now);
    var saved = workOrders.saveAndFlush(entity);

    statusHistory.saveAndFlush(historyRow(workOrderId, WorkOrderStatus.OPEN, WorkOrderStatus.ASSIGNED, user, now));
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ORDER,
        auditEntityId(workOrderId), workOrderId, machine.getPlant().getId(), previous, auditValues(saved), null));
    return WorkOrderMapper.toDomain(saved);
  }

  // -------------------------------------------------------------------------
  // Gates & scope
  // -------------------------------------------------------------------------

  private void requireCreateRole(AuthenticatedUser user) {
    switch (user.applicationRole()) {
      case SUPER_ADMIN, MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER, STAFF_MAINTENANCE,
          PRODUCTION_LEADER -> {
        // allowed
      }
      case TECHNICIAN, AUDITOR, INVENTORY_MAINTENANCE, STOREKEEPER -> throw new WorkorderForbiddenException();
    }
  }

  private void requireCreateAccess(AuthenticatedUser user, MachineEntity machine,
      WorkOrderCategoryEntity category) {
    switch (user.applicationRole()) {
      case SUPER_ADMIN -> {
        // unrestricted
      }
      case SECTION_LEADER -> {
        var scope = scopes.derive(user);
        if (!groupInScope(scope, machine)) {
          throw new WorkorderForbiddenException();
        }
      }
      case PRODUCTION_LEADER -> {
        if (!BREAKDOWN_CATEGORY_CODE.equals(category.getCode())) {
          throw new BreakdownCategoryRequiredException();
        }
        plantScopes.requirePlantAccess(user, machine.getPlant().getId());
      }
      default -> plantScopes.requirePlantAccess(user, machine.getPlant().getId());
    }
  }

  private void requireAssignRole(AuthenticatedUser user) {
    switch (user.applicationRole()) {
      case SUPER_ADMIN, MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER -> {
        // allowed
      }
      case STAFF_MAINTENANCE, TECHNICIAN, INVENTORY_MAINTENANCE, STOREKEEPER, PRODUCTION_LEADER, AUDITOR ->
          throw new WorkorderForbiddenException();
    }
  }

  /**
   * Assign scope: SECTION_LEADER may only assign inside their own machine groups (same as
   * create — FR-110 parity); the other leadership roles (plant-scoped) may assign when the
   * machine's group OR its plant is in scope.
   */
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
    return scope.machineGroupIds().contains(machine.getMachineGroup().getId())
        || scope.activeTeamIds().contains(machine.getMachineGroup().getId());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /**
   * Keyed replay within the 5-minute window returns the existing workorder scoped to the
   * same creator; {@link Optional#empty()} otherwise. The key is trimmed once so padded
   * variants cannot defeat the unique index (V48).
   */
  private Optional<WorkOrderEntity> replayIfIdempotent(String idempotencyKey, UUID userId, Instant now) {
    if (idempotencyKey == null) {
      return Optional.empty();
    }
    return workOrders.findByIdempotencyKeyAndCreatedByAndCreatedAtAfter(
        idempotencyKey, userId, now.minus(IDEMPOTENCY_WINDOW));
  }

  private String normalizedKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return null;
    }
    return idempotencyKey.trim();
  }

  private boolean isIdempotencyKeyViolation(DataIntegrityViolationException exception) {
    var cause = exception.getCause();
    while (cause != null) {
      if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
          && "uq_work_orders_idempotency_key".equalsIgnoreCase(constraint.getConstraintName())) {
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

  /** Trace id for history/audit correlation; falls back to a fresh UUID when none is stashed. */
  private String traceId() {
    return PolicyDecisionPoint.currentTraceId();
  }

  /** Workorder ids are VARCHAR PKs; audit needs a stable UUID per id for correlation. */
  private UUID auditEntityId(String workOrderId) {
    return UUID.nameUUIDFromBytes(workOrderId.getBytes(StandardCharsets.UTF_8));
  }

  private String normalizeCategoryCode(String code) {
    return code.trim().toUpperCase();
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

  public record CreateWorkOrderCommand(String categoryCode, UUID machineId, String description, String parentId,
      String idempotencyKey) {
  }

  public record AssignWorkOrderCommand(UUID assigneeUserId) {
  }

  /** Create outcome; {@code replay} is true when the request was deduped against a prior key. */
  public record CreateResult(WorkOrder workorder, boolean replay) {
  }

  public static class WorkorderForbiddenException extends RuntimeException {
  }

  public static class BreakdownCategoryRequiredException extends RuntimeException {
  }

  public static class WorkOrderMachineNotFoundException extends RuntimeException {
  }

  public static class WorkOrderCategoryNotFoundException extends RuntimeException {
  }

  public static class WorkOrderParentNotFoundException extends RuntimeException {
  }

  public static class WorkOrderNotFoundException extends RuntimeException {
  }

  public static class WorkOrderUserNotFoundException extends RuntimeException {
  }

  public static class InvalidStateTransitionException extends RuntimeException {
  }

  public static class SelfAssignmentForbiddenException extends RuntimeException {
  }
}
