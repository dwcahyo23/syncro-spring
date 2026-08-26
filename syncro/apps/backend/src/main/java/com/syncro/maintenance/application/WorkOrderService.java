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
import com.syncro.maintenance.domain.workorder.RepairSession;
import com.syncro.maintenance.domain.workorder.WorkOrder;
import com.syncro.maintenance.domain.workorder.WorkOrderIdGenerator;
import com.syncro.maintenance.domain.workorder.WorkOrderStateMachine;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.RepairSessionEntity;
import com.syncro.maintenance.infrastructure.db.RepairSessionRepository;
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
import java.util.List;
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
  static final String HISTORY_SOURCE_DERIVED = "DERIVED";
  static final String SYSTEM_ACTOR = "SYSTEM";
  static final Duration IDEMPOTENCY_WINDOW = Duration.ofMinutes(5);

  private final WorkOrderIdGenerator idGenerator;
  private final WorkOrderRepository workOrders;
  private final WorkOrderStatusHistoryRepository statusHistory;
  private final WorkOrderCategoryRepository categories;
  private final MachineRepository machines;
  private final AuthUserRepository users;
  private final OperationalScopeService scopes;
  private final PlantScopeService plantScopes;
  private final SparepartRequestReadinessPort sparepartReadiness;
  private final AuditLogWriter auditLog;
  private final RepairSessionRepository repairSessions;
  private final Clock clock;

  public WorkOrderService(WorkOrderIdGenerator idGenerator, WorkOrderRepository workOrders,
      WorkOrderStatusHistoryRepository statusHistory, WorkOrderCategoryRepository categories,
      MachineRepository machines, AuthUserRepository users, OperationalScopeService scopes,
      PlantScopeService plantScopes, SparepartRequestReadinessPort sparepartReadiness, AuditLogWriter auditLog,
      RepairSessionRepository repairSessions, Clock clock) {
    this.idGenerator = idGenerator;
    this.workOrders = workOrders;
    this.statusHistory = statusHistory;
    this.categories = categories;
    this.machines = machines;
    this.users = users;
    this.scopes = scopes;
    this.plantScopes = plantScopes;
    this.sparepartReadiness = sparepartReadiness;
    this.auditLog = auditLog;
    this.repairSessions = repairSessions;
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

  /**
   * Manual status transition (FR-114, story 10.3). Valid edges come from the AD-4 table;
   * the actor gate is executor (assigned TECHNICIAN/STAFF_MAINTENANCE) OR an in-scope
   * leader for start/resume/complete, leader-only otherwise. Manual ON_PROCUREMENT
   * placement/resume is blocked while a live non-READY sparepart request exists (AD-5),
   * and {@code DONE → CLOSED} enforces the parent-close rule (FR-120/AD-3).
   */
  @Transactional
  public WorkOrder transition(AuthenticatedUser user, String workOrderId, TransitionWorkOrderCommand command) {
    var entity = workOrders.findByIdForUpdate(workOrderId).orElseThrow(WorkOrderNotFoundException::new);
    if (!SOURCE_INTERNAL.equals(entity.getSource())) {
      // SYNCED workorders are owned by the sync module (FR-111); manual transitions would
      // diverge from the external sheet's lifecycle without a sync_version bump.
      throw new WorkorderForbiddenException();
    }
    var toStatus = command.toStatus();
    if (toStatus == WorkOrderStatus.ASSIGNED) {
      // OPEN → ASSIGNED is exclusive to POST /{id}/assign (10.2).
      throw new InvalidStateTransitionException();
    }
    var fromStatus = entity.getStatus();
    if (!WorkOrderStateMachine.can(fromStatus, toStatus)) {
      throw new InvalidStateTransitionException();
    }
    var machine = machines.findByIdWithPlantAndGroup(entity.getMachineId())
        .orElseThrow(WorkOrderMachineNotFoundException::new);
    requireTransitionAccess(user, entity, fromStatus, toStatus, machine);
    if (isProcurementSensitive(fromStatus, toStatus) && sparepartReadiness.hasLiveNonReadyRequest(workOrderId)) {
      throw new ProcurementRequestConflictException();
    }
    enforceDoneGate(entity, toStatus, command.reason());

    var previous = auditValues(entity);
    var now = Instant.now(clock);
    var overrideReason = enforceParentTerminalOnClose(entity, toStatus, user, command.overrideReason());

    entity.transitionTo(toStatus, now);
    var saved = workOrders.saveAndFlush(entity);

    statusHistory.saveAndFlush(historyRow(workOrderId, fromStatus, toStatus, user, now));
    var newValue = auditValues(saved);
    if (command.reason() != null) {
      newValue.put("reason", command.reason());
    }
    if (entity.getDoneReason() != null) {
      newValue.put("doneReason", entity.getDoneReason());
    }
    if (overrideReason != null) {
      newValue.put("overrideReason", overrideReason);
    }
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ORDER,
        auditEntityId(workOrderId), workOrderId, machine.getPlant().getId(), previous, newValue, null));
    return WorkOrderMapper.toDomain(saved);
  }

  /**
   * Recomputes the derived ON_PROCUREMENT state (AD-5, story 10.3). Takes a row lock on
   * the workorder (serializes per-workorder derivations), applies only when the current
   * status is IN_PROGRESS or ON_PROCUREMENT, and writes a DERIVED/SYSTEM history row on
   * every applied change. Idempotent: no history row when the state is unchanged. Epic 12
   * implements {@link SparepartRequestReadinessPort} and calls this on request transitions
   * — derivation is event-driven, never a poller.
   */
  @Transactional
  public void recomputeProcurementState(String workOrderId) {
    var entity = workOrders.findByIdForUpdate(workOrderId).orElseThrow(WorkOrderNotFoundException::new);
    if (!SOURCE_INTERNAL.equals(entity.getSource())) {
      // Derived transitions never touch SYNCED workorders (FR-111) — same invariant as
      // the manual path, so a future Epic-12 recompute cannot diverge the external sheet.
      return;
    }
    var status = entity.getStatus();
    if (status != WorkOrderStatus.IN_PROGRESS && status != WorkOrderStatus.ON_PROCUREMENT) {
      return;
    }
    var target = sparepartReadiness.hasLiveNonReadyRequest(workOrderId)
        ? WorkOrderStatus.ON_PROCUREMENT
        : WorkOrderStatus.IN_PROGRESS;
    if (target == status) {
      return;
    }
    var now = Instant.now(clock);
    entity.transitionTo(target, now);
    workOrders.saveAndFlush(entity);
    statusHistory.saveAndFlush(derivedHistoryRow(workOrderId, status, target, now));
  }

  // -------------------------------------------------------------------------
  // Repair sessions & MTTR (10.4)
  // -------------------------------------------------------------------------

  /**
   * Starts a repair session (FR-115/AD-6, story 10.4). Sessions are local operational
   * fields — never touching status or sync_version — so they are allowed on SYNCED
   * workorders (AD-3 "preserved"), but the status gate (IN_PROGRESS) and the
   * executor/leader access gate still apply. The DB EXCLUDE constraint is the
   * authoritative backstop; the overlap pre-check gives a friendly 409 first. On the
   * first-ever session start the SLA response time (OPEN → first session start) is
   * computed from status history and persisted on the workorder (source-agnostic).
   */
  @Transactional
  public RepairSessionsResult startSession(AuthenticatedUser user, String workOrderId,
      StartSessionCommand command) {
    var entity = workOrders.findByIdForUpdate(workOrderId).orElseThrow(WorkOrderNotFoundException::new);
    if (entity.getStatus() != WorkOrderStatus.IN_PROGRESS) {
      throw new WorkorderNotInProgressException();
    }
    var machine = machines.findByIdWithPlantAndGroup(entity.getMachineId())
        .orElseThrow(WorkOrderMachineNotFoundException::new);
    requireSessionAccess(user, entity, machine);

    if (repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(workOrderId).isPresent()) {
      throw new SessionAlreadyOpenException();
    }
    var now = Instant.now(clock);
    checkNoOverlappingSession(workOrderId, now);

    var sessionId = UUID.randomUUID();
    var session = new RepairSessionEntity(sessionId, workOrderId, UUID.fromString(user.id()),
        command.description(), now, null, null, now, now);
    repairSessions.saveAndFlush(session);

    if (entity.getResponseTimeMinutes() == null) {
      statusHistory.findFirstOpenTransitionedAt(workOrderId)
          .ifPresent(openedAt -> {
            entity.setResponseTimeMinutes(Duration.between(openedAt, now).toMinutes());
            workOrders.saveAndFlush(entity);
          });
    }

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.REPAIR_SESSION,
        auditEntityId("session-" + sessionId), workOrderId, machine.getPlant().getId(), null, sessionValues(session),
        null));
    return toSessionResult(entity, workOrderId);
  }

  /**
   * Stops the open repair session (FR-115/AD-6). Closing a time interval is always safe,
   * so stop is allowed in any status; the executor/leader access gate still applies.
   * MTTR is recomputed (SUM of completed durations) and persisted on the workorder so
   * dashboards can query it without recomputing.
   */
  @Transactional
  public RepairSessionsResult stopSession(AuthenticatedUser user, String workOrderId) {
    var entity = workOrders.findByIdForUpdate(workOrderId).orElseThrow(WorkOrderNotFoundException::new);
    var machine = machines.findByIdWithPlantAndGroup(entity.getMachineId())
        .orElseThrow(WorkOrderMachineNotFoundException::new);
    requireSessionAccess(user, entity, machine);

    var session = repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(workOrderId)
        .orElseThrow(NoOpenSessionException::new);
    var now = Instant.now(clock);
    var previous = sessionValues(session);
    session.close(now, Duration.between(session.getStartedAt(), now).toMinutes(), now);
    repairSessions.saveAndFlush(session);

    entity.setMttrMinutes(repairSessions.sumCompletedDuration(workOrderId));
    workOrders.saveAndFlush(entity);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.REPAIR_SESSION,
        auditEntityId("session-" + session.getId()), workOrderId, machine.getPlant().getId(), previous,
        sessionValues(session), null));
    return toSessionResult(entity, workOrderId);
  }

  /** Lists the workorder's repair sessions ordered by startedAt asc (any authenticated user). */
  @Transactional(readOnly = true)
  public RepairSessionsResult listSessions(String workOrderId) {
    var entity = workOrders.findById(workOrderId).orElseThrow(WorkOrderNotFoundException::new);
    return toSessionResult(entity, workOrderId);
  }

  /**
   * FR-115 DONE gate (additive on 10.3): an open session blocks DONE; with no completed
   * session at all a non-blank documented reason is required. Non-blank reasons are
   * persisted on the workorder and ride the audit {@code new_value} JSON.
   */
  private void enforceDoneGate(WorkOrderEntity entity, WorkOrderStatus toStatus, String reason) {
    if (toStatus != WorkOrderStatus.DONE) {
      return;
    }
    if (repairSessions.findFirstByWorkOrderIdAndEndedAtIsNull(entity.getId()).isPresent()) {
      throw new SessionOpenConflictException();
    }
    var hasCompletedSession = repairSessions.sumCompletedDuration(entity.getId()) > 0;
    if (!hasCompletedSession && (reason == null || reason.isBlank())) {
      throw new DoneWithoutSessionReasonRequiredException();
    }
    if (reason != null && !reason.isBlank()) {
      entity.setDoneReason(reason.trim());
    }
  }

  /** Friendly pre-check that the latest session does not overlap the new open interval. */
  private void checkNoOverlappingSession(String workOrderId, Instant startedAt) {
    var latest = repairSessions.findByWorkOrderIdOrderByStartedAtAsc(workOrderId);
    if (latest.isEmpty()) {
      return;
    }
    var newest = latest.get(latest.size() - 1);
    if (newest.getEndedAt() != null && newest.getEndedAt().isAfter(startedAt)) {
      // The new open range would overlap the latest completed session; the DB EXCLUDE
      // constraint is the authoritative backstop for anything this check misses.
      throw new SessionOverlapException();
    }
  }

  /** Executor (assigned TECHNICIAN/STAFF_MAINTENANCE) OR in-scope leader, same as transitions. */
  private void requireSessionAccess(AuthenticatedUser user, WorkOrderEntity entity, MachineEntity machine) {
    if (isInScopeLeader(user, machine)) {
      return;
    }
    if (isExecutor(user, entity)) {
      return;
    }
    throw new WorkorderForbiddenException();
  }

  private Map<String, Object> sessionValues(RepairSessionEntity session) {
    var values = new HashMap<String, Object>();
    values.put("id", session.getId());
    values.put("workOrderId", session.getWorkOrderId());
    values.put("technicianId", session.getTechnicianId());
    values.put("startedAt", session.getStartedAt());
    values.put("endedAt", session.getEndedAt());
    values.put("durationMinutes", session.getDurationMinutes());
    return values;
  }

  private RepairSession toSession(RepairSessionEntity entity) {
    return new RepairSession(entity.getId(), entity.getWorkOrderId(), entity.getTechnicianId(),
        entity.getDescription(), entity.getStartedAt(), entity.getEndedAt(), entity.getDurationMinutes());
  }

  private RepairSessionsResult toSessionResult(WorkOrderEntity workOrder, String workOrderId) {
    var sessions = repairSessions.findByWorkOrderIdOrderByStartedAtAsc(workOrderId).stream()
        .map(this::toSession)
        .toList();
    return new RepairSessionsResult(WorkOrderMapper.toDomain(workOrder), sessions);
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

  /**
   * Transition actor gate (FR-114). In-scope leaders may perform every valid manual
   * transition; the assigned executor may only start (ASSIGNED→IN_PROGRESS), resume
   * (ON_PROCUREMENT→IN_PROGRESS) and complete (IN_PROGRESS→DONE). The gate is
   * authoritative — the rego only does role-level default-deny on the transition path.
   */
  private void requireTransitionAccess(AuthenticatedUser user, WorkOrderEntity entity,
      WorkOrderStatus fromStatus, WorkOrderStatus toStatus, MachineEntity machine) {
    if (isInScopeLeader(user, machine)) {
      return;
    }
    if (executorMay(fromStatus, toStatus) && isExecutor(user, entity)) {
      return;
    }
    throw new WorkorderForbiddenException();
  }

  /** The three start/resume/complete transitions the assigned executor may perform. */
  private static boolean executorMay(WorkOrderStatus from, WorkOrderStatus to) {
    return (from == WorkOrderStatus.ASSIGNED && to == WorkOrderStatus.IN_PROGRESS)
        || (from == WorkOrderStatus.ON_PROCUREMENT && to == WorkOrderStatus.IN_PROGRESS)
        || (from == WorkOrderStatus.IN_PROGRESS && to == WorkOrderStatus.DONE);
  }

  private boolean isExecutor(AuthenticatedUser user, WorkOrderEntity entity) {
    return entity.getAssignedTechnicianId() != null
        && UUID.fromString(user.id()).equals(entity.getAssignedTechnicianId())
        && (user.applicationRole() == ApplicationRole.TECHNICIAN
            || user.applicationRole() == ApplicationRole.STAFF_MAINTENANCE);
  }

  /**
   * Leader scope: SECTION_LEADER is group-in-scope only (FR-110 parity with create/assign);
   * MAINTENANCE_LEADER/MANAGER_MAINTENANCE may act on group-in-scope OR plant scope;
   * SUPER_ADMIN is unrestricted; every other role is not a leader.
   */
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

  /** Placement to ON_PROCUREMENT or resume to IN_PROGRESS both touch the request state. */
  private static boolean isProcurementSensitive(WorkOrderStatus from, WorkOrderStatus to) {
    return (from == WorkOrderStatus.IN_PROGRESS && to == WorkOrderStatus.ON_PROCUREMENT)
        || (from == WorkOrderStatus.ON_PROCUREMENT && to == WorkOrderStatus.IN_PROGRESS);
  }

  /**
   * FR-120/AD-3: closing a parent requires every child to be CLOSED or CANCELLED. Children
   * are read under {@code SELECT ... FOR UPDATE} in the same transaction; a workorder with
   * no children trivially passes. SUPER_ADMIN and MANAGER_MAINTENANCE may override a
   * non-terminal child when an override reason is provided; the reason rides the audit
   * {@code new_value} JSON. Returns the normalized override reason when the close was
   * overridden, {@code null} otherwise.
   */
  private String enforceParentTerminalOnClose(WorkOrderEntity entity, WorkOrderStatus toStatus,
      AuthenticatedUser user, String overrideReason) {
    if (toStatus != WorkOrderStatus.CLOSED) {
      return null;
    }
    var children = workOrders.findByParentIdForUpdate(entity.getId());
    var allTerminal = children.stream()
        .allMatch(child -> child.getStatus() == WorkOrderStatus.CLOSED
            || child.getStatus() == WorkOrderStatus.CANCELLED);
    if (allTerminal) {
      return null;
    }
    var overrideEligible = user.applicationRole() == ApplicationRole.SUPER_ADMIN
        || user.applicationRole() == ApplicationRole.MANAGER_MAINTENANCE;
    if (!overrideEligible) {
      throw new ChildrenNotTerminalException();
    }
    if (overrideReason == null || overrideReason.isBlank()) {
      throw new OverrideReasonRequiredException();
    }
    return overrideReason.trim();
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

  /** Derived transitions (AD-5) are recorded with source DERIVED and actor SYSTEM. */
  private WorkOrderStatusHistoryEntity derivedHistoryRow(String workOrderId, WorkOrderStatus from,
      WorkOrderStatus to, Instant transitionedAt) {
    return new WorkOrderStatusHistoryEntity(UUID.randomUUID(), workOrderId, from.name(), to.name(),
        HISTORY_SOURCE_DERIVED, SYSTEM_ACTOR, traceId(), transitionedAt);
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

  public record TransitionWorkOrderCommand(WorkOrderStatus toStatus, String reason, String overrideReason) {
  }

  public record StartSessionCommand(String description) {
  }

  /** Create outcome; {@code replay} is true when the request was deduped against a prior key. */
  public record CreateResult(WorkOrder workorder, boolean replay) {
  }

  public record RepairSessionsResult(WorkOrder workOrder, List<RepairSession> sessions) {
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

  public static class ProcurementRequestConflictException extends RuntimeException {
  }

  public static class ChildrenNotTerminalException extends RuntimeException {
  }

  public static class OverrideReasonRequiredException extends RuntimeException {
  }

  public static class WorkorderNotInProgressException extends RuntimeException {
  }

  public static class SessionAlreadyOpenException extends RuntimeException {
  }

  public static class SessionOverlapException extends RuntimeException {
  }

  public static class NoOpenSessionException extends RuntimeException {
  }

  public static class SessionOpenConflictException extends RuntimeException {
  }

  public static class DoneWithoutSessionReasonRequiredException extends RuntimeException {
  }
}
