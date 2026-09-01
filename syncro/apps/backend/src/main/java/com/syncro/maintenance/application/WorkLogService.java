package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderMachineNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderForbiddenException;
import com.syncro.maintenance.domain.workorder.WorkLogStoppedReason;
import com.syncro.maintenance.infrastructure.db.RepairSessionRepository;
import com.syncro.maintenance.infrastructure.db.WorkAssignmentRepository;
import com.syncro.maintenance.infrastructure.db.WorkLogEntity;
import com.syncro.maintenance.infrastructure.db.WorkLogRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Work-log use cases (blueprint B4, AD-18, story 17-2). Every work log is recorded
 * against an active assignment on the workorder; the technician_id is derived from
 * the assignment, never from the request. Backdate is allowed but never before the
 * workorder's created_at. Completing a work log (end_time + stopped_reason) recomputes
 * cumulative MTTR (repair sessions + work logs) and persists it on the workorder.
 *
 * <p>Role gate mirrors the session/transition gates: executor (TECHNICIAN/STAFF_MAINTENANCE
 * with an active assignment on the WO) OR in-scope leader may create/edit; any authenticated
 * user may list. EXTERNAL workorders are rejected.
 */
@Service
public class WorkLogService {

  private final WorkLogRepository workLogs;
  private final WorkAssignmentRepository workAssignments;
  private final WorkOrderRepository workOrders;
  private final RepairSessionRepository repairSessions;
  private final MachineRepository machines;
  private final OperationalScopeService scopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public WorkLogService(WorkLogRepository workLogs, WorkAssignmentRepository workAssignments,
      WorkOrderRepository workOrders, RepairSessionRepository repairSessions,
      MachineRepository machines, OperationalScopeService scopes,
      AuditLogWriter auditLog, Clock clock) {
    this.workLogs = workLogs;
    this.workAssignments = workAssignments;
    this.workOrders = workOrders;
    this.repairSessions = repairSessions;
    this.machines = machines;
    this.scopes = scopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /**
   * Creates a work log against an active assignment on the workorder. The technician_id
   * is always derived from the assignment (never from the request). Backdate is allowed
   * but must not be before the workorder's created_at. When the work log is completed
   * (end_time + stopped_reason) the cumulative MTTR is recomputed and persisted.
   */
  @Transactional
  public WorkLogView create(AuthenticatedUser user, String workOrderId, CreateWorkLogCommand command) {
    var entity = workOrders.findByIdForUpdate(workOrderId)
        .orElseThrow(WorkOrderNotFoundException::new);
    if (!"INTERNAL".equals(entity.getSource())) {
      throw new WorkorderForbiddenException();
    }

    var assignment = workAssignments.findById(command.workAssignmentId())
        .orElseThrow(WorkLogAssignmentNotFoundException::new);
    if (!assignment.getWorkOrderId().equals(workOrderId)) {
      throw new WorkLogAssignmentNotFoundException();
    }
    if (!assignment.isActive()) {
      throw new WorkLogAssignmentInactiveException();
    }

    if (command.startTime().isBefore(entity.getCreatedAt())) {
      throw new WorkLogBackdateBeforeWorkorderException();
    }
    if (command.endTime() != null && !command.endTime().isAfter(command.startTime())) {
      throw new WorkLogValidationException(Map.of("endTime",
          "endTime must be after startTime."));
    }
    if (command.activityNote() == null || command.activityNote().isBlank()) {
      throw new WorkLogValidationException(Map.of("activityNote",
          "activityNote is required."));
    }

    var machine = machines.findByIdWithPlantAndGroup(entity.getMachineId())
        .orElseThrow(WorkOrderMachineNotFoundException::new);
    requireWorkLogAccess(user, entity, machine, assignment.getTechnicianId());

    var now = Instant.now(clock);
    var logId = UUID.randomUUID();
    var log = new WorkLogEntity(logId, command.workAssignmentId(), workOrderId,
        assignment.getTechnicianId(), command.startTime(), command.endTime(),
        command.stoppedReason(), command.activityNote(), command.completionNote(),
        command.notes(), now, now);
    workLogs.saveAndFlush(log);

    if (command.endTime() != null) {
      entity.setMttrMinutes(recomputeMttr(workOrderId));
      workOrders.saveAndFlush(entity);
    }

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.WORK_LOG,
        logId, workOrderId, machine.getPlant().getId(), null, auditValues(log), null));

    return toView(log);
  }

  /**
   * Updates a work log's mutable fields. A non-leader may only edit their own log.
   * When end_time is set (completion), MTTR is recomputed.
   */
  @Transactional
  public WorkLogView update(AuthenticatedUser user, String workOrderId, UUID workLogId,
      UpdateWorkLogCommand command) {
    var entity = workOrders.findByIdForUpdate(workOrderId)
        .orElseThrow(WorkOrderNotFoundException::new);
    if (!"INTERNAL".equals(entity.getSource())) {
      throw new WorkorderForbiddenException();
    }

    var log = workLogs.findById(workLogId)
        .orElseThrow(WorkLogNotFoundException::new);
    if (!log.getWorkOrderId().equals(workOrderId)) {
      throw new WorkLogNotFoundException();
    }

    var machine = machines.findByIdWithPlantAndGroup(entity.getMachineId())
        .orElseThrow(WorkOrderMachineNotFoundException::new);
    if (!isInScopeLeader(user, machine)) {
      if (!isWorkLogExecutor(user, log.getTechnicianId(), entity.getAssignedTechnicianId())) {
        throw new WorkorderForbiddenException();
      }
    }

    // endTime is validated against the log's immutable startTime (AD-18 end > start).
    if (command.endTime() != null && !command.endTime().isAfter(log.getStartTime())) {
      throw new WorkLogValidationException(Map.of("endTime",
          "endTime must be after startTime."));
    }

    var previous = auditValues(log);
    var now = Instant.now(clock);
    log.update(command.endTime(), command.stoppedReason(), command.completionNote(),
        command.notes(), now);
    workLogs.saveAndFlush(log);

    if (command.endTime() != null) {
      entity.setMttrMinutes(recomputeMttr(workOrderId));
      workOrders.saveAndFlush(entity);
    }

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_LOG,
        workLogId, workOrderId, machine.getPlant().getId(), previous, auditValues(log), null));

    return toView(log);
  }

  /**
   * Lists the workorder's work logs ordered by start_time ascending. Any authenticated
   * user may read.
   */
  @Transactional(readOnly = true)
  public List<WorkLogView> list(String workOrderId) {
    if (!workOrders.existsById(workOrderId)) {
      throw new WorkOrderNotFoundException();
    }
    return workLogs.findByWorkOrderIdOrderByStartTimeAsc(workOrderId).stream()
        .map(this::toView)
        .toList();
  }

  // -------------------------------------------------------------------------
  // Gates & scope
  // -------------------------------------------------------------------------

  private void requireWorkLogAccess(AuthenticatedUser user, WorkOrderEntity workOrder,
      MachineEntity machine, UUID assignmentTechnicianId) {
    if (isInScopeLeader(user, machine)) {
      return;
    }
    if (isWorkLogExecutor(user, assignmentTechnicianId, workOrder.getAssignedTechnicianId())) {
      return;
    }
    throw new WorkorderForbiddenException();
  }

  private boolean isWorkLogExecutor(AuthenticatedUser user, UUID assignmentTechnicianId,
      UUID legacyAssignedTechnicianId) {
    if (user.applicationRole() != ApplicationRole.TECHNICIAN
        && user.applicationRole() != ApplicationRole.STAFF_MAINTENANCE) {
      return false;
    }
    var userId = UUID.fromString(user.id());
    // Executor = the active assignment's technician, or (legacy path) the workorder's
    // assigned_technician_id lead technician (AD-18 parity with requireSessionAccess).
    return userId.equals(assignmentTechnicianId) || userId.equals(legacyAssignedTechnicianId);
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

  // -------------------------------------------------------------------------
  // MTTR
  // -------------------------------------------------------------------------

  private long recomputeMttr(String workOrderId) {
    var repairDuration = repairSessions.sumCompletedDuration(workOrderId);
    var workLogDuration = workLogs.findByWorkOrderIdAndEndTimeIsNotNull(workOrderId).stream()
        .mapToLong(log -> Duration.between(log.getStartTime(), log.getEndTime()).toMinutes())
        .sum();
    return repairDuration + workLogDuration;
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private Map<String, Object> auditValues(WorkLogEntity entity) {
    var values = new HashMap<String, Object>();
    values.put("id", entity.getId());
    values.put("workOrderId", entity.getWorkOrderId());
    values.put("workAssignmentId", entity.getWorkAssignmentId());
    values.put("technicianId", entity.getTechnicianId());
    values.put("startTime", entity.getStartTime() != null ? entity.getStartTime().toString() : null);
    values.put("endTime", entity.getEndTime() != null ? entity.getEndTime().toString() : null);
    values.put("stoppedReason", entity.getStoppedReason() != null ? entity.getStoppedReason().name() : null);
    values.put("activityNote", entity.getActivityNote());
    values.put("completionNote", entity.getCompletionNote());
    values.put("notes", entity.getNotes());
    values.put("createdAt", entity.getCreatedAt().toString());
    values.put("updatedAt", entity.getUpdatedAt().toString());
    return values;
  }

  private WorkLogView toView(WorkLogEntity entity) {
    return new WorkLogView(entity.getId(), entity.getWorkAssignmentId(), entity.getWorkOrderId(),
        entity.getTechnicianId(), entity.getStartTime(), entity.getEndTime(),
        entity.getStoppedReason(), entity.getActivityNote(), entity.getCompletionNote(),
        entity.getNotes(), entity.getCreatedAt(), entity.getUpdatedAt());
  }

  // -------------------------------------------------------------------------
  // DTOs
  // -------------------------------------------------------------------------

  public record CreateWorkLogCommand(
      UUID workAssignmentId,
      Instant startTime,
      Instant endTime,
      WorkLogStoppedReason stoppedReason,
      String activityNote,
      String completionNote,
      String notes) {
  }

  public record UpdateWorkLogCommand(
      Instant endTime,
      WorkLogStoppedReason stoppedReason,
      String completionNote,
      String notes) {
  }

  public record WorkLogView(
      UUID id,
      UUID workAssignmentId,
      String workOrderId,
      UUID technicianId,
      Instant startTime,
      Instant endTime,
      WorkLogStoppedReason stoppedReason,
      String activityNote,
      String completionNote,
      String notes,
      Instant createdAt,
      Instant updatedAt) {
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  public static class WorkLogNotFoundException extends RuntimeException {
  }

  public static class WorkLogAssignmentNotFoundException extends RuntimeException {
  }

  public static class WorkLogAssignmentInactiveException extends RuntimeException {
  }

  public static class WorkLogBackdateBeforeWorkorderException extends RuntimeException {
  }

  /** Field-level validation failure (end before start, blank activity note). */
  public static class WorkLogValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public WorkLogValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new HashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}