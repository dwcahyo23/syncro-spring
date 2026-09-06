package com.syncro.maintenance.preventive.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.SignatureUseService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.maintenance.application.WorkOrderService;
import com.syncro.maintenance.preventive.application.PmChecklistService.PmChecklistItemNotFoundException;
import com.syncro.maintenance.preventive.domain.PmItemInputType;
import com.syncro.maintenance.preventive.domain.PmScheduleDateStatus;
import com.syncro.maintenance.preventive.domain.PmWorkOrderStatus;
import com.syncro.maintenance.preventive.infrastructure.db.PmChecklistCategoryRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmChecklistItemRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmExecutionEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmExecutionItemEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmExecutionItemRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmExecutionRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmScheduleDateEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmScheduleDateRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmScheduleRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmWorkOrderEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmWorkOrderRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PM execution service (story 19-5, blueprint F7/F8). Records the execution of an
 * IN_PROGRESS PM workorder: start (one execution per workorder — unique pm_wo_id,
 * pre-check + V1 uq backstop), per-item fill (snapshotting the checklist item onto
 * the execution-item row so results survive checksheet revision, then storing the
 * MEASUREMENT/OK_NG result), complete (NG rollup, workorder IN_PROGRESS→COMPLETED
 * via the 19-4 service, schedule date → EXECUTED, and one corrective workorder via
 * the 11-3 system-creation path for CRITICAL NG items, linked through
 * finding_wo_id/blocking_wo_id), and SPV verify (leader-gated sign-off).
 *
 * <p>Gates: start/fill/complete require caller == the execution's technician_id
 * (fixed at start from the workorder's assignee) or SUPER_ADMIN — a technician
 * executes only their own work; verify requires a leader role
 * (SECTION_LEADER/MAINTENANCE_LEADER/MANAGER_MAINTENANCE, SUPER_ADMIN bypass) with
 * the workorder's machine scope. Reads: the execution's technician, users with the
 * machine in scope, or SUPER_ADMIN.
 *
 * <p>Concurrency: every mutation loads the execution row through
 * {@code findByIdForUpdate} with PESSIMISTIC_WRITE (19-4 pattern), serializing
 * concurrent fill/complete/verify so exactly one caller wins; the unique pm_wo_id
 * index is the start race backstop.
 */
@Service
public class PmExecutionService {

  private static final String EXECUTION_UNIQUE_INDEX = "uq_pm_executions_pm_wo";
  private static final String SCHEDULE_DATE_UNIQUE_INDEX = "uq_pm_executions_schedule_date";

  private static final String SIGNATURE_MODULE = "preventive";
  private static final String SUBJECT_TYPE_PM_EXECUTION = "PM_EXECUTION";
  private static final String VERIFY_ACTION = "VERIFY_EXECUTION";

  /** Breakdown category for corrective finding workorders (story 19-5 design note). */
  private static final String FINDING_CATEGORY_CODE = "01";

  private final PmExecutionRepository executions;
  private final PmExecutionItemRepository executionItems;
  private final PmWorkOrderRepository workOrders;
  private final PmWorkOrderService pmWorkOrders;
  private final PmChecklistItemRepository checklistItems;
  private final PmChecklistCategoryRepository checklistCategories;
  private final PmScheduleDateRepository scheduleDates;
  private final PmScheduleRepository schedules;
  private final MachineRepository machines;
  private final WorkOrderService workOrderSystem;
  private final AuditLogWriter auditLog;
  private final OperationalScopeService scopes;
  private final SignatureUseService signatureUses;
  private final Clock clock;

  public PmExecutionService(PmExecutionRepository executions,
      PmExecutionItemRepository executionItems, PmWorkOrderRepository workOrders,
      PmWorkOrderService pmWorkOrders, PmChecklistItemRepository checklistItems,
      PmChecklistCategoryRepository checklistCategories, PmScheduleDateRepository scheduleDates,
      PmScheduleRepository schedules, MachineRepository machines, WorkOrderService workOrderSystem,
      AuditLogWriter auditLog, OperationalScopeService scopes, SignatureUseService signatureUses, Clock clock) {
    this.executions = executions;
    this.executionItems = executionItems;
    this.workOrders = workOrders;
    this.pmWorkOrders = pmWorkOrders;
    this.checklistItems = checklistItems;
    this.checklistCategories = checklistCategories;
    this.scheduleDates = scheduleDates;
    this.schedules = schedules;
    this.machines = machines;
    this.workOrderSystem = workOrderSystem;
    this.auditLog = auditLog;
    this.scopes = scopes;
    this.signatureUses = signatureUses;
    this.clock = clock;
  }

  // -------------------------------------------------------------------------
  // Start (IN_PROGRESS workorder → one execution)
  // -------------------------------------------------------------------------

  @Transactional
  public ExecutionView start(AuthenticatedUser user, UUID pmWoId) {
    // Lock the workorder row first: the assignee gate, the IN_PROGRESS precondition
    // and the one-execution pre-check must all observe committed state (19-4
    // loadForUpdate pattern).
    var workOrder = workOrders.findByIdForUpdate(pmWoId)
        .orElseThrow(PmWorkOrderService.PmWorkOrderNotFoundException::new);
    var machine = loadMachine(workOrder.getMachineId());
    requireAssignee(user, workOrder.getAssignedTechnicianId());
    if (workOrder.getStatus() != PmWorkOrderStatus.IN_PROGRESS
        || workOrder.getAssignedTechnicianId() == null) {
      // A manually seeded IN_PROGRESS row without an assignee has no technician to
      // bind — the execution's technician_id is NOT NULL, so treat it as not
      // executable rather than a 500.
      throw new InvalidExecutionStateException();
    }
    if (executions.existsByPmWoId(pmWoId)) {
      throw new ExecutionAlreadyExistsException();
    }
    var now = Instant.now(clock);
    // technician_id is fixed at start from the workorder's assignee (the gate above
    // already proved the caller is that assignee or SUPER_ADMIN); an IN_PROGRESS
    // workorder always has one (ASSIGNED precedes IN_PROGRESS in 19-4's lifecycle).
    var technicianId = workOrder.getAssignedTechnicianId();
    var scheduleDateId = resolveScheduleDateId(workOrder, machine);
    var entity = new PmExecutionEntity(UUID.randomUUID(), pmWoId, scheduleDateId, technicianId,
        null, null, null, null, null, now, null, false, 0, null, now, now);
    PmExecutionEntity saved;
    try {
      saved = executions.saveAndFlush(entity);
    } catch (DataIntegrityViolationException exception) {
      // Lost a start race to a concurrent caller: either the same workorder
      // (uq_pm_executions_pm_wo) or two workorders resolving to one schedule date
      // (uq_pm_executions_schedule_date) → 409, never a raw 500.
      var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
      if (message.contains(EXECUTION_UNIQUE_INDEX)
          || message.contains(SCHEDULE_DATE_UNIQUE_INDEX)) {
        throw new ExecutionAlreadyExistsException();
      }
      throw exception;
    }
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.PM_EXECUTION,
        saved.getId(), label(saved, machine), machine.getPlant().getId(), null,
        executionValues(saved), null));
    return toView(saved, List.of());
  }

  // -------------------------------------------------------------------------
  // Fill (snapshot the checklist item + record the result)
  // -------------------------------------------------------------------------

  @Transactional
  public ExecutionItemView fill(AuthenticatedUser user, UUID id, UUID checklistItemId,
      FillExecutionCommand command) {
    var execution = loadForUpdate(id);
    var workOrder = loadWorkOrder(execution.getPmWoId());
    var machine = loadMachine(workOrder.getMachineId());
    requireExecutionTechnician(user, execution);
    if (execution.getCompletedAt() != null) {
      throw new InvalidExecutionTransitionException();
    }
    if (workOrder.getStatus() != PmWorkOrderStatus.IN_PROGRESS) {
      // The workorder left IN_PROGRESS (e.g. the overdue sweep) between start and
      // fill — results can no longer be recorded against an execution whose work
      // is no longer executable.
      throw new InvalidExecutionTransitionException();
    }
    // The fill target must be an item of the workorder's checksheet revision — the
    // snapshot source. An item from another checksheet is reported as not-found so
    // the surface never probes foreign revisions.
    var source = checklistItems.findById(checklistItemId)
        .orElseThrow(PmChecklistItemNotFoundException::new);
    if (workOrder.getTemplateId() == null
        || !workOrder.getTemplateId().equals(source.getChecksheetId())) {
      throw new PmChecklistItemNotFoundException();
    }
    var fieldErrors = new LinkedHashMap<String, String>();
    var actualValue = command.actualValue();
    var ok = command.ok();
    var ng = command.ng() != null && command.ng();
    if (source.getInputType() == PmItemInputType.MEASUREMENT && actualValue == null) {
      fieldErrors.put("actualValue", "actualValue is required for MEASUREMENT items.");
    }
    if (source.getInputType() == PmItemInputType.OK_NG) {
      if (ok == null && command.ng() == null) {
        fieldErrors.put("ok", "ok or ng is required for OK_NG items.");
      } else if (ok != null && command.ng() != null && ok.equals(command.ng())) {
        // Exactly one of ok/ng must be true: false+false and true+true are both 400.
        fieldErrors.put("ok", "Exactly one of ok or ng must be true for OK_NG items.");
      }
    }
    var ngNotes = normalizeOptional(command.ngNotes());
    if (ng && ngNotes == null) {
      fieldErrors.put("ngNotes", "ngNotes is required when an item is NG.");
    }
    if (command.blocked() && normalizeOptional(command.blockingWoCode()) == null) {
      fieldErrors.put("blockingWoCode", "blockingWoCode is required when an item is blocked.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new ExecutionValidationException(Map.copyOf(fieldErrors));
    }
    var now = Instant.now(clock);
    var existing = executionItems.findByExecutionIdAndChecklistItemId(id, checklistItemId);
    var previous = existing.map(PmExecutionService::itemValues).orElse(null);
    PmExecutionItemEntity item;
    if (existing.isPresent()) {
      item = existing.get();
    } else {
      var categoryName = source.getCategoryId() == null ? null
          : checklistCategories.findById(source.getCategoryId())
              .map(c -> c.getName()).orElse(null);
      item = new PmExecutionItemEntity(UUID.randomUUID(), id, source.getId(),
          source.getSequence(), categoryName, source.getParameterText(),
          source.getCheckMethod(), source.getInputType(), source.isCriticalFlag(),
          source.getUnit(), source.getLsl(), source.getNominal(), source.getUsl(),
          null, null, false, null, null, false, null, null, null, null, null, null, null,
          null, now, now);
    }
    item.fill(actualValue, ok, ng, ngNotes, normalizeOptional(command.ngPhotoUrl()),
        command.blocked(), normalizeOptional(command.blockingWoCode()), null, now, now);
    var saved = executionItems.saveAndFlush(item);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_EXECUTION_ITEM,
        saved.getId(), itemLabel(saved), machine.getPlant().getId(), previous,
        itemValues(saved), null));
    return toItemView(saved);
  }

  // -------------------------------------------------------------------------
  // Complete (rollup + workorder COMPLETED + schedule date EXECUTED + finding WO)
  // -------------------------------------------------------------------------

  @Transactional
  public ExecutionView complete(AuthenticatedUser user, UUID id) {
    var execution = loadForUpdate(id);
    var workOrder = loadWorkOrder(execution.getPmWoId());
    var machine = loadMachine(workOrder.getMachineId());
    requireExecutionTechnician(user, execution);
    if (execution.getCompletedAt() != null) {
      throw new InvalidExecutionTransitionException();
    }
    var items = executionItems.findByExecutionIdOrderBySequenceAsc(id);
    var filled = items.stream().filter(i -> i.getFilledAt() != null).toList();
    if (filled.isEmpty()) {
      throw new InvalidExecutionTransitionException();
    }
    var previous = executionValues(execution);
    var now = Instant.now(clock);
    var ngItems = filled.stream().filter(PmExecutionItemEntity::isNg).toList();
    var hasNg = !ngItems.isEmpty();

    // Workorder IN_PROGRESS→COMPLETED through the 19-4 service (its own assignee
    // gate is already satisfied — the execution's technician is the assignee — and
    // it writes the PM_WORK_ORDER audit UPDATE).
    pmWorkOrders.complete(user, execution.getPmWoId(), null);

    // Schedule date → EXECUTED when the execution is linked to one (a manually
    // created workorder has no period match — documented skip, not an error).
    if (execution.getScheduleDateId() != null) {
      var date = scheduleDates.findById(execution.getScheduleDateId()).orElse(null);
      if (date != null && date.getStatus() != PmScheduleDateStatus.EXECUTED) {
        var datePrevious = scheduleDateValues(date);
        date.transitionTo(PmScheduleDateStatus.EXECUTED, now);
        scheduleDates.saveAndFlush(date);
        auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
            AuditEntityType.PM_SCHEDULE_DATE, date.getId(), dateLabel(date, machine),
            machine.getPlant().getId(), datePrevious, scheduleDateValues(date), null));
      }
    }

    // One corrective workorder for the whole execution when any CRITICAL item is NG
    // (11-3 system path, null preventive_schedule_id — the partial unique index
    // tolerates nulls). Its audit is WorkOrderService's own recordSystem.
    // Idempotent on retry: a finding WO already linked to this execution is reused
    // (createSystem commits in its own REQUIRES_NEW transaction, so a failed outer
    // transaction can leave an orphan finding WO — accepted, 11-3 precedent: the
    // corrective action must survive the rollback of the recording transaction).
    var criticalNg = ngItems.stream().filter(PmExecutionItemEntity::isCriticalFlag).toList();
    String findingWoId = execution.getFindingWoId();
    if (!criticalNg.isEmpty() && findingWoId == null) {
      findingWoId = workOrderSystem.createSystem(machine.getId(), FINDING_CATEGORY_CODE,
          "PM finding: " + criticalNg.getFirst().getParameterText(), null);
    }
    if (!criticalNg.isEmpty() && findingWoId != null) {
      for (var item : criticalNg) {
        if (findingWoId.equals(item.getBlockingWoId())) {
          continue;
        }
        var itemPrevious = itemValues(item);
        item.linkBlockingWo(findingWoId, now);
        var linked = executionItems.saveAndFlush(item);
        auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
            AuditEntityType.PM_EXECUTION_ITEM, linked.getId(), itemLabel(linked),
            machine.getPlant().getId(), itemPrevious, itemValues(linked), null));
      }
    }

    execution.complete(now, hasNg, ngItems.size(), now);
    if (findingWoId != null) {
      execution.setFindingWoId(findingWoId, now);
    }
    var saved = executions.saveAndFlush(execution);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_EXECUTION,
        saved.getId(), label(saved, machine), machine.getPlant().getId(), previous,
        executionValues(saved), null));
    return toView(saved, savedItems(saved.getId()));
  }

  // -------------------------------------------------------------------------
  // Verify (SPV sign-off)
  // -------------------------------------------------------------------------

  @Transactional
  public ExecutionView verify(AuthenticatedUser user, UUID id, UUID spvSignatureId) {
    return verify(user, id, spvSignatureId, null, null);
  }

  /**
   * SPV sign-off (story 19-5). Story 22-3: the signed verification also writes one
   * {@code signature_uses} row (module=preventive) + SIGNATURE_USE audit via
   * {@link SignatureUseService}. An unknown/absent stored signature leaves the use row's
   * reference columns null (pm_executions.spv_signature_id carries no FK — the existing
   * contract accepts any UUID).
   */
  @Transactional
  public ExecutionView verify(AuthenticatedUser user, UUID id, UUID spvSignatureId, String ipAddress,
      String userAgent) {
    var execution = loadForUpdate(id);
    var workOrder = loadWorkOrder(execution.getPmWoId());
    var machine = loadMachine(workOrder.getMachineId());
    requireLeaderMutationAccess(user, machine);
    if (execution.getCompletedAt() == null) {
      throw new InvalidExecutionTransitionException();
    }
    if (execution.getSpvVerifierId() != null) {
      throw new InvalidExecutionTransitionException();
    }
    var previous = executionValues(execution);
    var now = Instant.now(clock);
    execution.verifyBySpv(UUID.fromString(user.id()), spvSignatureId, now, now);
    var saved = executions.saveAndFlush(execution);

    // Review 22-3 P6: the verify body is optional (19-5) — a no-signature verify is
    // not a signature application, so no use row is written for it.
    if (spvSignatureId != null) {
      signatureUses.record(user, spvSignatureId, null, SIGNATURE_MODULE, SUBJECT_TYPE_PM_EXECUTION,
          saved.getId().toString(), VERIFY_ACTION, null, ipAddress, userAgent);
    }

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_EXECUTION,
        saved.getId(), label(saved, machine), machine.getPlant().getId(), previous,
        executionValues(saved), null));
    return toView(saved, savedItems(saved.getId()));
  }

  // -------------------------------------------------------------------------
  // List / Get (scope-filtered reads)
  // -------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<ExecutionView> list(AuthenticatedUser user, UUID pmWoId, UUID technicianId) {
    List<PmExecutionEntity> rows;
    if (pmWoId != null) {
      rows = executions.findByPmWoId(pmWoId).stream().toList();
    } else if (technicianId != null) {
      rows = executions.findByTechnicianIdOrderByStartedAtDesc(technicianId);
    } else {
      rows = executions.findAll();
    }
    var result = new ArrayList<ExecutionView>();
    for (var execution : rows) {
      var workOrder = workOrders.findById(execution.getPmWoId()).orElse(null);
      if (workOrder == null) {
        continue;
      }
      var machine = machines.findByIdWithPlantAndGroup(workOrder.getMachineId()).orElse(null);
      if (machine == null || !canRead(user, execution, machine)) {
        continue;
      }
      result.add(toView(execution, savedItems(execution.getId())));
    }
    return result;
  }

  @Transactional(readOnly = true)
  public ExecutionView get(AuthenticatedUser user, UUID id) {
    var execution = executions.findById(id).orElseThrow(PmExecutionNotFoundException::new);
    var workOrder = loadWorkOrder(execution.getPmWoId());
    var machine = loadMachine(workOrder.getMachineId());
    if (!canRead(user, execution, machine)) {
      throw new PmExecutionForbiddenException();
    }
    return toView(execution, savedItems(execution.getId()));
  }

  // -------------------------------------------------------------------------
  // Gates & scope
  // -------------------------------------------------------------------------

  /** Read gate: SUPER_ADMIN, the execution's technician, or the machine in scope. */
  private boolean canRead(AuthenticatedUser user, PmExecutionEntity execution,
      MachineEntity machine) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return true;
    }
    if (execution.getTechnicianId().equals(UUID.fromString(user.id()))) {
      return true;
    }
    return inScope(scopes.derive(user), machine);
  }

  /** Start gate: only the workorder's assigned technician (or SUPER_ADMIN) may start. */
  private void requireAssignee(AuthenticatedUser user, UUID assignedTechnicianId) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (assignedTechnicianId == null || !assignedTechnicianId.equals(UUID.fromString(user.id()))) {
      throw new PmExecutionForbiddenException();
    }
  }

  /** Fill/complete gate: caller == the execution's technician_id (fixed at start) or SUPER_ADMIN. */
  private void requireExecutionTechnician(AuthenticatedUser user, PmExecutionEntity execution) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (!execution.getTechnicianId().equals(UUID.fromString(user.id()))) {
      throw new PmExecutionForbiddenException();
    }
  }

  /**
   * Verify gate: SECTION_LEADER/MAINTENANCE_LEADER/MANAGER_MAINTENANCE with the
   * machine's plant/group scope; SUPER_ADMIN bypass (19-4
   * requireLeaderMutationAccess parity).
   */
  private void requireLeaderMutationAccess(AuthenticatedUser user, MachineEntity machine) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    var scope = scopes.derive(user);
    switch (user.applicationRole()) {
      case SECTION_LEADER -> {
        if (!groupInScope(scope, machine)) {
          throw new PmExecutionForbiddenException();
        }
      }
      case MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {
        if (!plantInScope(scope, machine) && !groupInScope(scope, machine)) {
          throw new PmExecutionForbiddenException();
        }
      }
      default -> throw new PmExecutionForbiddenException();
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
  // Loaders & resolution
  // -------------------------------------------------------------------------

  private List<PmExecutionItemEntity> savedItems(UUID executionId) {
    return executionItems.findByExecutionIdOrderBySequenceAsc(executionId);
  }

  private PmExecutionEntity loadForUpdate(UUID id) {
    return executions.findByIdForUpdate(id).orElseThrow(PmExecutionNotFoundException::new);
  }

  private PmWorkOrderEntity loadWorkOrder(UUID id) {
    return workOrders.findById(id).orElseThrow(PmWorkOrderService.PmWorkOrderNotFoundException::new);
  }

  private MachineEntity loadMachine(UUID machineId) {
    return machines.findByIdWithPlantAndGroup(machineId)
        .orElseThrow(PmWorkOrderService.MachineNotFoundException::new);
  }

  /**
   * Resolve the schedule date for the workorder's period (machine, template/checksheet,
   * scheduled_date) — the same triple that generated the workorder in 19-4. No match
   * (manually created workorder) → null; the EXECUTED step is then skipped at complete.
   */
  private UUID resolveScheduleDateId(PmWorkOrderEntity workOrder, MachineEntity machine) {
    if (workOrder.getTemplateId() == null || workOrder.getScheduledDate() == null) {
      return null;
    }
    var schedule = schedules.findByPlantIdAndMachineIdAndChecksheetIdAndYear(
            machine.getPlant().getId(), workOrder.getMachineId(), workOrder.getTemplateId(),
            workOrder.getScheduledDate().getYear())
        .orElse(null);
    if (schedule == null) {
      return null;
    }
    return scheduleDates.findByScheduleIdAndPlannedDate(schedule.getId(),
            workOrder.getScheduledDate())
        .map(PmScheduleDateEntity::getId)
        .orElse(null);
  }

  // -------------------------------------------------------------------------
  // Validation helpers
  // -------------------------------------------------------------------------

  /** null → null; blank → null; otherwise trimmed. */
  private static String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  // -------------------------------------------------------------------------
  // Audit value maps & views (all UUIDs stringified — 19-1 pattern)
  // -------------------------------------------------------------------------

  private static String label(PmExecutionEntity entity, MachineEntity machine) {
    return (machine != null ? machine.getCode() : "?") + "@exec:" + entity.getId();
  }

  private static String dateLabel(PmScheduleDateEntity date, MachineEntity machine) {
    return (machine != null ? machine.getCode() : "?") + "@" + date.getPlannedDate();
  }

  private static Map<String, Object> executionValues(PmExecutionEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", entity.getId().toString());
    values.put("pmWoId", entity.getPmWoId().toString());
    values.put("scheduleDateId", entity.getScheduleDateId() != null
        ? entity.getScheduleDateId().toString() : null);
    values.put("technicianId", entity.getTechnicianId().toString());
    values.put("spvVerifierId", entity.getSpvVerifierId() != null
        ? entity.getSpvVerifierId().toString() : null);
    values.put("technicianSignatureId", entity.getTechnicianSignatureId() != null
        ? entity.getTechnicianSignatureId().toString() : null);
    values.put("technicianSignedAt", entity.getTechnicianSignedAt() != null
        ? entity.getTechnicianSignedAt().toString() : null);
    values.put("spvSignatureId", entity.getSpvSignatureId() != null
        ? entity.getSpvSignatureId().toString() : null);
    values.put("spvSignedAt", entity.getSpvSignedAt() != null
        ? entity.getSpvSignedAt().toString() : null);
    values.put("startedAt", entity.getStartedAt().toString());
    values.put("completedAt", entity.getCompletedAt() != null
        ? entity.getCompletedAt().toString() : null);
    values.put("hasNgItems", entity.hasNgItems());
    values.put("ngCount", entity.getNgCount());
    values.put("findingWoId", entity.getFindingWoId());
    values.put("createdAt", entity.getCreatedAt().toString());
    values.put("updatedAt", entity.getUpdatedAt().toString());
    return values;
  }

  private static Map<String, Object> itemValues(PmExecutionItemEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", entity.getId().toString());
    values.put("executionId", entity.getExecutionId().toString());
    values.put("checklistItemId", entity.getChecklistItemId() != null
        ? entity.getChecklistItemId().toString() : null);
    values.put("sequence", entity.getSequence());
    values.put("categoryName", entity.getCategoryName());
    values.put("parameterText", entity.getParameterText());
    values.put("checkMethod", entity.getCheckMethod());
    values.put("inputType", entity.getInputType() != null ? entity.getInputType().name() : null);
    values.put("isCriticalFlag", entity.isCriticalFlag());
    values.put("unit", entity.getUnit());
    values.put("lsl", entity.getLsl() != null ? entity.getLsl().toPlainString() : null);
    values.put("nominal", entity.getNominal() != null ? entity.getNominal().toPlainString() : null);
    values.put("usl", entity.getUsl() != null ? entity.getUsl().toPlainString() : null);
    values.put("actualValue", entity.getActualValue() != null
        ? entity.getActualValue().toPlainString() : null);
    values.put("isOk", entity.getOk());
    values.put("isNg", entity.isNg());
    values.put("ngNotes", entity.getNgNotes());
    values.put("ngPhotoUrl", entity.getNgPhotoUrl());
    values.put("isBlocked", entity.isBlocked());
    values.put("blockedWoCode", entity.getBlockedWoCode());
    values.put("blockingWoId", entity.getBlockingWoId());
    values.put("filledAt", entity.getFilledAt() != null ? entity.getFilledAt().toString() : null);
    values.put("createdAt", entity.getCreatedAt().toString());
    values.put("updatedAt", entity.getUpdatedAt().toString());
    return values;
  }

  private static Map<String, Object> scheduleDateValues(PmScheduleDateEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", entity.getId().toString());
    values.put("scheduleId", entity.getScheduleId().toString());
    values.put("plannedDate", entity.getPlannedDate().toString());
    values.put("status", entity.getStatus().name());
    values.put("createdAt", entity.getCreatedAt().toString());
    values.put("updatedAt", entity.getUpdatedAt().toString());
    return values;
  }

  /**
   * entity_label is VARCHAR(255) NOT NULL — VARCHAR counts characters (code points),
   * so the guard must compare codePointCount, not String.length(): a surrogate-heavy
   * text can exceed 255 UTF-16 units while staying under 255 code points (offsetByCodePoints
   * would throw on it). Truncation is code-point-safe.
   */
  private static String itemLabel(PmExecutionItemEntity entity) {
    var text = entity.getParameterText();
    if (text == null) {
      return entity.getId().toString();
    }
    if (text.codePointCount(0, text.length()) <= 255) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, 255));
  }

  private static ExecutionView toView(PmExecutionEntity entity,
      List<PmExecutionItemEntity> items) {
    return new ExecutionView(entity.getId(), entity.getPmWoId(), entity.getScheduleDateId(),
        entity.getTechnicianId(), entity.getSpvVerifierId(), entity.getTechnicianSignatureId(),
        entity.getTechnicianSignedAt(), entity.getSpvSignatureId(), entity.getSpvSignedAt(),
        entity.getStartedAt(), entity.getCompletedAt(), entity.hasNgItems(), entity.getNgCount(),
        entity.getFindingWoId(), entity.getCreatedAt(), entity.getUpdatedAt(),
        items.stream().map(PmExecutionService::toItemView).toList());
  }

  private static ExecutionItemView toItemView(PmExecutionItemEntity entity) {
    return new ExecutionItemView(entity.getId(), entity.getExecutionId(),
        entity.getChecklistItemId(), entity.getSequence(), entity.getCategoryName(),
        entity.getParameterText(), entity.getCheckMethod(), entity.getInputType(),
        entity.isCriticalFlag(), entity.getUnit(), entity.getLsl(), entity.getNominal(),
        entity.getUsl(), entity.getActualValue(), entity.getOk(), entity.isNg(),
        entity.getNgNotes(), entity.getNgPhotoUrl(), entity.isBlocked(),
        entity.getBlockedWoCode(), entity.getBlockingWoId(), entity.getFilledAt(),
        entity.getCreatedAt(), entity.getUpdatedAt());
  }

  // -------------------------------------------------------------------------
  // Commands & views
  // -------------------------------------------------------------------------

  public record FillExecutionCommand(BigDecimal actualValue, Boolean ok, Boolean ng,
      String ngNotes, String ngPhotoUrl, boolean blocked, String blockingWoCode) {
  }

  public record ExecutionView(UUID id, UUID pmWoId, UUID scheduleDateId, UUID technicianId,
      UUID spvVerifierId, UUID technicianSignatureId, Instant technicianSignedAt,
      UUID spvSignatureId, Instant spvSignedAt, Instant startedAt, Instant completedAt,
      boolean hasNgItems, int ngCount, String findingWoId, Instant createdAt, Instant updatedAt,
      List<ExecutionItemView> items) {
  }

  public record ExecutionItemView(UUID id, UUID executionId, UUID checklistItemId, int sequence,
      String categoryName, String parameterText, String checkMethod, PmItemInputType inputType,
      boolean isCriticalFlag, String unit, BigDecimal lsl, BigDecimal nominal, BigDecimal usl,
      BigDecimal actualValue, Boolean isOk, boolean isNg, String ngNotes, String ngPhotoUrl,
      boolean isBlocked, String blockedWoCode, String blockingWoId, Instant filledAt,
      Instant createdAt, Instant updatedAt) {
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  public static class PmExecutionNotFoundException extends RuntimeException {
  }

  public static class PmExecutionForbiddenException extends RuntimeException {
  }

  /** Second start on a workorder that already has an execution → 409. */
  public static class ExecutionAlreadyExistsException extends RuntimeException {
  }

  /** Start against a workorder that is not IN_PROGRESS → 409. */
  public static class InvalidExecutionStateException extends RuntimeException {
  }

  /** Fill after complete, complete with zero filled items, verify before/after verify → 409. */
  public static class InvalidExecutionTransitionException extends RuntimeException {
  }

  public static class ExecutionValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public ExecutionValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}
