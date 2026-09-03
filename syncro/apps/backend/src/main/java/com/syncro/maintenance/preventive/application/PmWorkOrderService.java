package com.syncro.maintenance.preventive.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.maintenance.preventive.domain.PmScheduleStatus;
import com.syncro.maintenance.preventive.domain.PmWorkOrderStatus;
import com.syncro.maintenance.preventive.infrastructure.db.PmScheduleDateRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmScheduleEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmScheduleRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmWorkOrderEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmWorkOrderRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PM workorder service (story 19-4, blueprint F6). Turns an ACTIVE schedule's
 * {@code pm_schedule_dates} into executable workorders (generate — idempotent per
 * (machine, template, scheduled_date) period via a pre-check plus the V10 partial
 * unique index as the race backstop) and drives the strictly sequential lifecycle
 * SCHEDULED → ASSIGNED → IN_PROGRESS → COMPLETED, with a Clock-based overdue sweep
 * moving any non-terminal workorder whose scheduled_date has passed to OVERDUE.
 * The sweep is an endpoint, not a scheduler — the operator/cron calls it.
 *
 * <p>Gates: generate/assign/sweep-overdue require a leader role
 * (SECTION_LEADER/MAINTENANCE_LEADER/MANAGER_MAINTENANCE, SUPER_ADMIN bypass) with
 * the machine's plant/group scope; start/complete are assignee-scoped — only the
 * workorder's {@code assigned_technician_id} (or SUPER_ADMIN) may execute their own
 * work. Reads are scope-filtered like 19-1/19-2/19-3.
 *
 * <p>Concurrency: every mutation loads the workorder row through
 * {@code findByIdForUpdate} with PESSIMISTIC_WRITE (19-3 pattern), serializing
 * concurrent transitions so exactly one caller wins and the loser sees the
 * committed state and throws.
 */
@Service
public class PmWorkOrderService {

  private static final String PERIOD_UNIQUE_INDEX = "uq_pm_work_orders_period";

  /** Non-terminal statuses eligible for the overdue sweep. */
  private static final List<PmWorkOrderStatus> SWEEPABLE_STATUSES =
      List.of(PmWorkOrderStatus.SCHEDULED, PmWorkOrderStatus.ASSIGNED,
          PmWorkOrderStatus.IN_PROGRESS);

  private final PmWorkOrderRepository workOrders;
  private final PmScheduleRepository schedules;
  private final PmScheduleDateRepository scheduleDates;
  private final MachineRepository machines;
  private final AuthUserRepository users;
  private final AuditLogWriter auditLog;
  private final OperationalScopeService scopes;
  private final Clock clock;

  public PmWorkOrderService(PmWorkOrderRepository workOrders, PmScheduleRepository schedules,
      PmScheduleDateRepository scheduleDates, MachineRepository machines,
      AuthUserRepository users, AuditLogWriter auditLog, OperationalScopeService scopes,
      Clock clock) {
    this.workOrders = workOrders;
    this.schedules = schedules;
    this.scheduleDates = scheduleDates;
    this.machines = machines;
    this.users = users;
    this.auditLog = auditLog;
    this.scopes = scopes;
    this.clock = clock;
  }

  // -------------------------------------------------------------------------
  // Generate (ACTIVE schedule dates → SCHEDULED workorders, idempotent per period)
  // -------------------------------------------------------------------------

  @Transactional
  public List<WorkOrderView> generate(AuthenticatedUser user, UUID scheduleId) {
    // Lock the schedule row FIRST so the ACTIVE precondition and the whole
    // batch serialize (19-3 transitionDate pattern): concurrent generates on one
    // schedule cannot both enter the loop. A per-date existsBy pre-check alone
    // cannot observe an uncommitted sibling insert, so without this lock both
    // callers would hit the V10 unique index mid-loop; catching that per-date
    // cannot recover because the loser's transaction is already aborted. The
    // second caller waits on this lock, then sees every period and creates zero
    // (true idempotency, 19-4 RACE-003).
    var schedule = schedules.findByIdForUpdate(scheduleId)
        .orElseThrow(PmScheduleNotFoundException::new);
    var machine = loadMachine(schedule.getMachineId());
    requireLeaderMutationAccess(user, machine);
    if (schedule.getStatus() != PmScheduleStatus.ACTIVE) {
      throw new InvalidScheduleStateException();
    }
    var now = Instant.now(clock);
    var created = new ArrayList<WorkOrderView>();
    for (var date : scheduleDates.findByScheduleIdOrderByPlannedDateAsc(schedule.getId())) {
      // Idempotency pre-check: one workorder per (machine, template, planned_date).
      // A concurrent generate racing into the same period hits the V10 partial
      // unique index; the saveWorkOrder backstop converts that 409 at the loop
      // level so the second caller observes a per-date skip, not an abort.
      if (workOrders.existsByMachineIdAndTemplateIdAndScheduledDate(
          schedule.getMachineId(), schedule.getChecksheetId(), date.getPlannedDate())) {
        continue;
      }
      var entity = new PmWorkOrderEntity(
          UUID.randomUUID(), schedule.getMachineId(), schedule.getChecksheetId(),
          schedule.getFrequencyId(), schedule.getFrequencyCode(), schedule.getFrequencyName(),
          schedule.getChecksheetRevisionNo(), PmWorkOrderStatus.SCHEDULED, null,
          date.getPlannedDate(), null, null, null, now, now);
      try {
        var saved = saveWorkOrder(entity);
        auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.PM_WORK_ORDER,
            saved.getId(), label(saved, machine), machine.getPlant().getId(), null,
            workOrderValues(saved), null));
        created.add(toView(saved));
      } catch (WorkOrderPeriodExistsException duplicate) {
        // Lost the race on this period to a concurrent generate: the period now
        // exists, so skip it — the end state is identical either way (idempotent).
        continue;
      }
    }
    return created;
  }

  // -------------------------------------------------------------------------
  // Lifecycle: assign → start → complete (strictly sequential)
  // -------------------------------------------------------------------------

  @Transactional
  public WorkOrderView assign(AuthenticatedUser user, UUID id, UUID technicianId) {
    var entity = loadForUpdate(id);
    var machine = loadMachine(entity.getMachineId());
    requireLeaderMutationAccess(user, machine);
    requireState(entity, PmWorkOrderStatus.SCHEDULED);
    if (!users.existsById(technicianId)) {
      throw new TechnicianNotFoundException();
    }
    var previous = workOrderValues(entity);
    entity.assign(technicianId, Instant.now(clock));
    var saved = workOrders.saveAndFlush(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_WORK_ORDER,
        saved.getId(), label(saved, machine), machine.getPlant().getId(),
        previous, workOrderValues(saved), null));
    return toView(saved);
  }

  @Transactional
  public WorkOrderView start(AuthenticatedUser user, UUID id) {
    var entity = loadForUpdate(id);
    var machine = loadMachine(entity.getMachineId());
    requireAssignee(user, entity);
    requireState(entity, PmWorkOrderStatus.ASSIGNED);
    var previous = workOrderValues(entity);
    var now = Instant.now(clock);
    entity.start(now, now);
    var saved = workOrders.saveAndFlush(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_WORK_ORDER,
        saved.getId(), label(saved, machine), machine.getPlant().getId(),
        previous, workOrderValues(saved), null));
    return toView(saved);
  }

  @Transactional
  public WorkOrderView complete(AuthenticatedUser user, UUID id, String certificateUrl) {
    var entity = loadForUpdate(id);
    var machine = loadMachine(entity.getMachineId());
    requireAssignee(user, entity);
    requireState(entity, PmWorkOrderStatus.IN_PROGRESS);
    var previous = workOrderValues(entity);
    var now = Instant.now(clock);
    entity.complete(now, certificateUrl, now);
    var saved = workOrders.saveAndFlush(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_WORK_ORDER,
        saved.getId(), label(saved, machine), machine.getPlant().getId(),
        previous, workOrderValues(saved), null));
    return toView(saved);
  }

  // -------------------------------------------------------------------------
  // Overdue sweep (endpoint-triggered, Clock-decided — no scheduler)
  // -------------------------------------------------------------------------

  @Transactional
  public int sweepOverdue(AuthenticatedUser user) {
    requireLeaderRole(user);
    var cutoff = LocalDate.now(clock);
    var candidates = workOrders.findByStatusInAndScheduledDateBefore(SWEEPABLE_STATUSES, cutoff);
    var scope = user.applicationRole() == ApplicationRole.SUPER_ADMIN ? null : scopes.derive(user);
    var swept = 0;
    for (var candidate : candidates) {
      // Lock + re-check per row so a concurrent complete/assign cannot be clobbered.
      var entity = loadForUpdate(candidate.getId());
      if (!SWEEPABLE_STATUSES.contains(entity.getStatus())
          || !entity.getScheduledDate().isBefore(cutoff)) {
        continue;
      }
      var machine = loadMachine(entity.getMachineId());
      if (scope != null && !inScope(scope, machine)) {
        continue;
      }
      var previous = workOrderValues(entity);
      entity.markOverdue(Instant.now(clock));
      var saved = workOrders.saveAndFlush(entity);
      auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_WORK_ORDER,
          saved.getId(), label(saved, machine), machine.getPlant().getId(),
          previous, workOrderValues(saved), null));
      swept++;
    }
    return swept;
  }

  // -------------------------------------------------------------------------
  // List / Get (scope-filtered reads)
  // -------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<WorkOrderView> list(AuthenticatedUser user, PmWorkOrderStatus status,
      UUID machineId) {
    if (machineId != null) {
      loadMachine(machineId);
    }
    var scope = scopes.derive(user);
    var rows = workOrders.findAll().stream()
        .filter(w -> status == null || w.getStatus() == status)
        .filter(w -> machineId == null || w.getMachineId().equals(machineId))
        .sorted(java.util.Comparator.comparing(PmWorkOrderEntity::getScheduledDate,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
            .thenComparing(PmWorkOrderEntity::getId))
        .toList();
    if (scope.plantIds() == null) {
      return rows.stream().map(PmWorkOrderService::toView).toList();
    }
    var result = new ArrayList<WorkOrderView>();
    for (var workOrder : rows) {
      var machine = machines.findByIdWithPlantAndGroup(workOrder.getMachineId());
      if (machine.isPresent() && inScope(scope, machine.get())) {
        result.add(toView(workOrder));
      }
    }
    return result;
  }

  @Transactional(readOnly = true)
  public WorkOrderView get(AuthenticatedUser user, UUID id) {
    var entity = loadWorkOrder(id);
    var machine = loadMachine(entity.getMachineId());
    if (!canRead(user, machine)) {
      throw new PmWorkOrderForbiddenException();
    }
    return toView(entity);
  }

  // -------------------------------------------------------------------------
  // Validation helpers
  // -------------------------------------------------------------------------

  /** Strictly sequential lifecycle: any other from-state → 409. */
  private static void requireState(PmWorkOrderEntity entity, PmWorkOrderStatus expected) {
    if (entity.getStatus() != expected) {
      throw new InvalidWorkOrderTransitionException();
    }
  }

  // -------------------------------------------------------------------------
  // Gates & scope
  // -------------------------------------------------------------------------

  /** Read gate: SUPER_ADMIN unrestricted; everyone else needs the machine in scope. */
  private boolean canRead(AuthenticatedUser user, MachineEntity machine) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return true;
    }
    return inScope(scopes.derive(user), machine);
  }

  /**
   * Gate: generate/assign by SECTION_LEADER/MAINTENANCE_LEADER/MANAGER_MAINTENANCE
   * with machine plant/group scope; SUPER_ADMIN bypass. STAFF_MAINTENANCE and every
   * other role are denied (19-3 requireLeaderRole parity + scope).
   */
  private void requireLeaderMutationAccess(AuthenticatedUser user, MachineEntity machine) {
    requireLeaderRole(user);
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    var scope = scopes.derive(user);
    switch (user.applicationRole()) {
      case SECTION_LEADER -> {
        if (!groupInScope(scope, machine)) {
          throw new PmWorkOrderForbiddenException();
        }
      }
      case MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {
        if (!plantInScope(scope, machine) && !groupInScope(scope, machine)) {
          throw new PmWorkOrderForbiddenException();
        }
      }
      default -> throw new PmWorkOrderForbiddenException();
    }
  }

  /** Role-only leader gate (sweep has no single machine to scope against). */
  private void requireLeaderRole(AuthenticatedUser user) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    switch (user.applicationRole()) {
      case SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {}
      default -> throw new PmWorkOrderForbiddenException();
    }
  }

  /** Execution gate: only the assigned technician (or SUPER_ADMIN) may start/complete. */
  private void requireAssignee(AuthenticatedUser user, PmWorkOrderEntity entity) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    var assignee = entity.getAssignedTechnicianId();
    if (assignee == null || !assignee.equals(UUID.fromString(user.id()))) {
      throw new PmWorkOrderForbiddenException();
    }
  }

  private boolean inScope(OperationalScope scope, MachineEntity machine) {
    return plantInScope(scope, machine) || groupInScope(scope, machine);
  }

  private boolean plantInScope(OperationalScope scope, MachineEntity machine) {
    return scope.plantIds() != null && scope.plantIds().contains(machine.getPlant().getId());
  }

  private boolean groupInScope(OperationalScope scope, MachineEntity machine) {
    return scope.machineGroupIds().contains(machine.getMachineGroup().getId())
        || scope.activeTeamIds().contains(machine.getMachineGroup().getId());
  }

  // -------------------------------------------------------------------------
  // Loaders & saves
  // -------------------------------------------------------------------------

  private MachineEntity loadMachine(UUID machineId) {
    return machines.findByIdWithPlantAndGroup(machineId)
        .orElseThrow(MachineNotFoundException::new);
  }

  private PmScheduleEntity loadSchedule(UUID id) {
    return schedules.findById(id).orElseThrow(PmScheduleNotFoundException::new);
  }

  /** Read-only load (GET). */
  private PmWorkOrderEntity loadWorkOrder(UUID id) {
    return workOrders.findById(id).orElseThrow(PmWorkOrderNotFoundException::new);
  }

  /**
   * Mutation load with PESSIMISTIC_WRITE row lock (19-3 pattern). Serializes
   * concurrent assign/start/complete/sweep so exactly one caller wins the state
   * check.
   */
  private PmWorkOrderEntity loadForUpdate(UUID id) {
    return workOrders.findByIdForUpdate(id).orElseThrow(PmWorkOrderNotFoundException::new);
  }

  /** Race backstop: uq_pm_work_orders_period → 409 WORK_ORDER_PERIOD_EXISTS. */
  private PmWorkOrderEntity saveWorkOrder(PmWorkOrderEntity entity) {
    try {
      return workOrders.saveAndFlush(entity);
    } catch (DataIntegrityViolationException exception) {
      var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
      if (message.contains(PERIOD_UNIQUE_INDEX)) {
        throw new WorkOrderPeriodExistsException();
      }
      throw exception;
    }
  }

  // -------------------------------------------------------------------------
  // Audit value maps & views (all UUIDs stringified — 19-1 pattern)
  // -------------------------------------------------------------------------

  private static String label(PmWorkOrderEntity entity, MachineEntity machine) {
    return (machine != null ? machine.getCode() : "?") + "@" + entity.getScheduledDate();
  }

  private static Map<String, Object> workOrderValues(PmWorkOrderEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", entity.getId().toString());
    values.put("machineId", entity.getMachineId().toString());
    values.put("templateId", entity.getTemplateId() != null ? entity.getTemplateId().toString() : null);
    values.put("frequencyId", entity.getFrequencyId() != null ? entity.getFrequencyId().toString() : null);
    values.put("frequencyCode", entity.getFrequencyCode());
    values.put("frequencyName", entity.getFrequencyName());
    values.put("templateRevision", entity.getTemplateRevision());
    values.put("status", entity.getStatus().name());
    values.put("assignedTechnicianId", entity.getAssignedTechnicianId() != null
        ? entity.getAssignedTechnicianId().toString() : null);
    values.put("scheduledDate", entity.getScheduledDate() != null
        ? entity.getScheduledDate().toString() : null);
    values.put("startedAt", entity.getStartedAt() != null ? entity.getStartedAt().toString() : null);
    values.put("completedAt", entity.getCompletedAt() != null ? entity.getCompletedAt().toString() : null);
    values.put("certificateUrl", entity.getCertificateUrl());
    values.put("createdAt", entity.getCreatedAt().toString());
    values.put("updatedAt", entity.getUpdatedAt().toString());
    return values;
  }

  private static WorkOrderView toView(PmWorkOrderEntity entity) {
    return new WorkOrderView(entity.getId(), entity.getMachineId(), entity.getTemplateId(),
        entity.getFrequencyId(), entity.getFrequencyCode(), entity.getFrequencyName(),
        entity.getTemplateRevision(), entity.getStatus(), entity.getAssignedTechnicianId(),
        entity.getScheduledDate(), entity.getStartedAt(), entity.getCompletedAt(),
        entity.getCertificateUrl(), entity.getCreatedAt(), entity.getUpdatedAt());
  }

  // -------------------------------------------------------------------------
  // Views
  // -------------------------------------------------------------------------

  public record WorkOrderView(UUID id, UUID machineId, UUID templateId, UUID frequencyId,
      String frequencyCode, String frequencyName, Integer templateRevision,
      PmWorkOrderStatus status, UUID assignedTechnicianId, LocalDate scheduledDate,
      Instant startedAt, Instant completedAt, String certificateUrl, Instant createdAt,
      Instant updatedAt) {
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  public static class PmWorkOrderNotFoundException extends RuntimeException {
  }

  public static class PmScheduleNotFoundException extends RuntimeException {
  }

  public static class MachineNotFoundException extends RuntimeException {
  }

  public static class TechnicianNotFoundException extends RuntimeException {
  }

  public static class PmWorkOrderForbiddenException extends RuntimeException {
  }

  /** Duplicate (machine, template, scheduled_date) period → 409. */
  public static class WorkOrderPeriodExistsException extends RuntimeException {
  }

  /** Any out-of-order lifecycle step (or terminal re-transition) → 409. */
  public static class InvalidWorkOrderTransitionException extends RuntimeException {
  }

  /** Generate against a non-ACTIVE schedule → 409. */
  public static class InvalidScheduleStateException extends RuntimeException {
  }
}
