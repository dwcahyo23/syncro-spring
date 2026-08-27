package com.syncro.maintenance.preventive.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.preventive.domain.ChecklistStatus;
import com.syncro.maintenance.preventive.domain.PreventiveChecklistItem;
import com.syncro.maintenance.preventive.domain.PreventiveChecklistResult;
import com.syncro.maintenance.preventive.domain.ScheduleStatus;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistItemEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistItemRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistResultEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistResultRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveProgramEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveProgramRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Preventive checklist completion &amp; leader approval (FR-132, story 11-2). A scoped
 * technician/staff submits one checklist per schedule (SCHEDULED → IN_PROGRESS); an
 * in-scope leader approves with a Garage signature key + signer identity + server
 * timestamp (IN_PROGRESS → PERFORMED) and the next due date rolls forward via
 * {@code PreventiveProgramService.rollForwardNext} (floating interval, AD-12). A leader
 * may also SKIP (no roll-forward). Rego is coarse default-deny; these service gates are
 * authoritative for scope and leader-only actions — mirroring the workorder services.
 */
@Service
public class PreventiveChecklistService {

  private final PreventiveScheduleRepository schedules;
  private final PreventiveProgramRepository programs;
  private final MachineRepository machines;
  private final PreventiveChecklistResultRepository results;
  private final PreventiveChecklistItemRepository items;
  private final PreventiveProgramService programService;
  private final AuditLogWriter auditLog;
  private final OperationalScopeService scopes;
  private final Clock clock;

  public PreventiveChecklistService(PreventiveScheduleRepository schedules, PreventiveProgramRepository programs,
      MachineRepository machines, PreventiveChecklistResultRepository results,
      PreventiveChecklistItemRepository items, PreventiveProgramService programService, AuditLogWriter auditLog,
      OperationalScopeService scopes, Clock clock) {
    this.schedules = schedules;
    this.programs = programs;
    this.machines = machines;
    this.results = results;
    this.items = items;
    this.programService = programService;
    this.auditLog = auditLog;
    this.scopes = scopes;
    this.clock = clock;
  }

  // -------------------------------------------------------------------------
  // Completion (technician/staff)
  // -------------------------------------------------------------------------

  /** Submits the checklist for a schedule (SCHEDULED → IN_PROGRESS). One result per schedule. */
  @Transactional
  public ChecklistResultView submit(AuthenticatedUser user, String scheduleId, ChecklistCommand command) {
    var schedule = loadSchedule(scheduleId);
    requireCompletionAccess(user, schedule);
    if (schedule.getStatus() == ScheduleStatus.PERFORMED || schedule.getStatus() == ScheduleStatus.SKIPPED) {
      throw new InvalidStateTransitionException();
    }
    if (results.existsByScheduleId(schedule.getId())) {
      throw new ChecklistAlreadySubmittedException();
    }
    validate(command);

    var now = Instant.now(clock);
    var saved = results.saveAndFlush(new PreventiveChecklistResultEntity(UUID.randomUUID(), schedule.getId(),
        UUID.fromString(user.id()), now, normalize(command.notes()), null, null, null, null, null, now, now));
    replaceItems(saved.getId(), command.items());
    schedule.transition(ScheduleStatus.IN_PROGRESS, now, UUID.fromString(user.id()));
    schedules.saveAndFlush(schedule);

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.PREVENTIVE_CHECKLIST,
        saved.getId(), entityLabel(schedule), machine(schedule).getPlant().getId(), null,
        resultValues(saved, command.items()), null));
    return toView(saved, toDomainItems(saved.getId(), command.items()));
  }

  /** Amends the checklist before approval (PUT): replaces items, keeps the result row. */
  @Transactional
  public ChecklistResultView amend(AuthenticatedUser user, String scheduleId, ChecklistCommand command) {
    var schedule = loadSchedule(scheduleId);
    requireCompletionAccess(user, schedule);
    if (schedule.getStatus() == ScheduleStatus.PERFORMED || schedule.getStatus() == ScheduleStatus.SKIPPED) {
      throw new InvalidStateTransitionException();
    }
    var result = results.findByScheduleId(schedule.getId())
        .orElseThrow(ChecklistNotSubmittedException::new);
    validate(command);

    var now = Instant.now(clock);
    replaceItems(result.getId(), command.items());
    var saved = results.saveAndFlush(result);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PREVENTIVE_CHECKLIST,
        saved.getId(), entityLabel(schedule), machine(schedule).getPlant().getId(), null,
        resultValues(saved, command.items()), null));
    return toView(saved, toDomainItems(saved.getId(), command.items()));
  }

  /** Reads the checklist result + items + derived status (any authenticated user). */
  @Transactional(readOnly = true)
  public ChecklistView get(String scheduleId) {
    var schedule = loadSchedule(scheduleId);
    var result = results.findByScheduleId(schedule.getId()).orElse(null);
    if (result == null) {
      return new ChecklistView(ChecklistStatus.NONE, null);
    }
    var itemValues = loadItems(result.getId());
    var status = result.getApprovedAt() != null ? ChecklistStatus.APPROVED : ChecklistStatus.SUBMITTED;
    return new ChecklistView(status, toView(result, itemValues));
  }

  // -------------------------------------------------------------------------
  // Approval & skip (leader)
  // -------------------------------------------------------------------------

  /** Leader approval: IN_PROGRESS → PERFORMED + roll forward the floating interval. */
  @Transactional
  public ChecklistResultView approve(AuthenticatedUser user, String scheduleId, ApproveCommand command) {
    var schedule = loadSchedule(scheduleId);
    requireLeaderAccess(user, schedule);
    if (schedule.getStatus() != ScheduleStatus.IN_PROGRESS) {
      throw new InvalidStateTransitionException();
    }
    if (command.signatureObjectKey() == null || command.signatureObjectKey().isBlank()) {
      throw new MissingSignatureException();
    }
    var result = results.findByScheduleId(schedule.getId())
        .orElseThrow(ChecklistNotSubmittedException::new);

    var now = Instant.now(clock);
    var signerIdentity = command.signerIdentity() == null || command.signerIdentity().isBlank()
        ? user.loginIdentifier() : command.signerIdentity().trim();
    result.approve(normalize(command.assessment()), command.signatureObjectKey().trim(), signerIdentity,
        UUID.fromString(user.id()), now, now);
    var saved = results.saveAndFlush(result);

    schedule.transition(ScheduleStatus.PERFORMED, now, UUID.fromString(user.id()));
    schedules.saveAndFlush(schedule);
    programService.rollForwardNext(schedule.getProgramId(), now);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PREVENTIVE_SCHEDULE,
        schedule.getId(), entityLabel(schedule), machine(schedule).getPlant().getId(),
        Map.<String, Object>of("status", "IN_PROGRESS"), Map.<String, Object>of("status", "PERFORMED"), null));
    return toView(saved, loadItems(saved.getId()));
  }

  /** Leader skip: SCHEDULED/IN_PROGRESS → SKIPPED, no roll-forward. */
  @Transactional
  public void skip(AuthenticatedUser user, String scheduleId) {
    var schedule = loadSchedule(scheduleId);
    requireLeaderAccess(user, schedule);
    if (schedule.getStatus() == ScheduleStatus.PERFORMED || schedule.getStatus() == ScheduleStatus.SKIPPED) {
      throw new InvalidStateTransitionException();
    }
    var now = Instant.now(clock);
    schedule.transition(ScheduleStatus.SKIPPED, now, UUID.fromString(user.id()));
    schedules.saveAndFlush(schedule);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PREVENTIVE_SCHEDULE,
        schedule.getId(), entityLabel(schedule), machine(schedule).getPlant().getId(),
        Map.<String, Object>of("status", schedule.getStatus().name()), Map.<String, Object>of("status", "SKIPPED"),
        null));
  }

  /** Derived checklist status for a schedule (calendar read, NFR-P2-2 backend-owned). */
  @Transactional(readOnly = true)
  public ChecklistStatus statusFor(UUID scheduleId) {
    var result = results.findByScheduleId(scheduleId).orElse(null);
    if (result == null) {
      return ChecklistStatus.NONE;
    }
    return result.getApprovedAt() != null ? ChecklistStatus.APPROVED : ChecklistStatus.SUBMITTED;
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static List<PreventiveChecklistItem> toDomainItems(UUID resultId, List<ItemCommand> commandItems) {
    var position = 0;
    var domain = new ArrayList<PreventiveChecklistItem>();
    for (var item : commandItems) {
      position += 1;
      domain.add(new PreventiveChecklistItem(UUID.randomUUID(), resultId, (short) position, item.label().trim(),
          normalize(item.value()), item.lsl(), item.usl(), normalize(item.note())));
    }
    return domain;
  }

  private void replaceItems(UUID resultId, List<ItemCommand> commandItems) {
    items.deleteByResultId(resultId);
    var now = Instant.now(clock);
    var position = 0;
    for (var item : commandItems) {
      position += 1;
      items.saveAndFlush(new PreventiveChecklistItemEntity(UUID.randomUUID(), resultId, (short) position,
          item.label().trim(), normalize(item.value()), item.lsl(), item.usl(), normalize(item.note()), now));
    }
  }

  private List<PreventiveChecklistItem> loadItems(UUID resultId) {
    return items.findByResultIdOrderByPositionAsc(resultId).stream()
        .map(e -> new PreventiveChecklistItem(e.getId(), e.getResultId(), e.getPosition(), e.getLabel(),
            e.getValue(), e.getLsl(), e.getUsl(), e.getNote()))
        .toList();
  }

  private void validate(ChecklistCommand command) {
    var fieldErrors = new LinkedHashMap<String, String>();
    if (command.items() == null || command.items().isEmpty()) {
      fieldErrors.put("items", "At least one checklist item is required.");
    } else {
      for (var item : command.items()) {
        var label = item.label() == null ? "" : item.label().trim();
        if (label.isEmpty()) {
          fieldErrors.put("label", "Item label must not be blank.");
        } else if (label.length() > 200) {
          fieldErrors.put("label", "Item label must be at most 200 characters.");
        }
        if (item.lsl() != null && item.usl() != null && item.lsl().compareTo(item.usl()) > 0) {
          fieldErrors.put("lsl", "LSL must not exceed USL.");
        }
      }
    }
    var notes = command.notes() == null ? "" : command.notes().trim();
    if (notes.length() > 4000) {
      fieldErrors.put("notes", "Notes must be at most 4000 characters.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new ChecklistValidationException(fieldErrors);
    }
  }

  /** Completion gate: any role with plant access to the schedule's machine (technician/staff/leader). */
  private void requireCompletionAccess(AuthenticatedUser user, PreventiveScheduleEntity schedule) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    var machine = machine(schedule);
    var scope = scopes.derive(user);
    var plantInScope = scope.plantIds() != null && scope.plantIds().contains(machine.getPlant().getId());
    if (plantInScope || groupInScope(scope, machine)) {
      return;
    }
    throw new ChecklistForbiddenException();
  }

  /** Leader gate: SECTION_LEADER/MAINTENANCE_LEADER/MANAGER_MAINTENANCE in scope. */
  private void requireLeaderAccess(AuthenticatedUser user, PreventiveScheduleEntity schedule) {
    switch (user.applicationRole()) {
      case SUPER_ADMIN -> {
        return;
      }
      case SECTION_LEADER -> {
        if (!groupInScope(scopes.derive(user), machine(schedule))) {
          throw new ChecklistForbiddenException();
        }
      }
      case MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {
        var scope = scopes.derive(user);
        var plantInScope = scope.plantIds() != null && scope.plantIds().contains(machine(schedule).getPlant().getId());
        if (!groupInScope(scope, machine(schedule)) && !plantInScope) {
          throw new ChecklistForbiddenException();
        }
      }
      default -> throw new ChecklistForbiddenException();
    }
  }

  private boolean groupInScope(OperationalScope scope, MachineEntity machine) {
    return scope.machineGroupIds().contains(machine.getMachineGroup().getId())
        || scope.activeTeamIds().contains(machine.getMachineGroup().getId());
  }

  private MachineEntity machine(PreventiveScheduleEntity schedule) {
    return machines.findByIdWithPlantAndGroup(schedule.getMachineId())
        .orElseThrow(ChecklistMachineNotFoundException::new);
  }

  private PreventiveProgramEntity program(PreventiveScheduleEntity schedule) {
    return programs.findById(schedule.getProgramId()).orElseThrow(ChecklistProgramNotFoundException::new);
  }

  private String entityLabel(PreventiveScheduleEntity schedule) {
    return program(schedule).getTitle() + " due " + schedule.getDueDate();
  }

  private PreventiveScheduleEntity loadSchedule(String id) {
    return schedules.findById(UUID.fromString(id)).orElseThrow(ScheduleNotFoundException::new);
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private Map<String, Object> resultValues(PreventiveChecklistResultEntity result, List<ItemCommand> commandItems) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", result.getId());
    values.put("scheduleId", result.getScheduleId());
    values.put("performedBy", result.getPerformedBy());
    values.put("itemCount", commandItems == null ? 0 : commandItems.size());
    return values;
  }

  private ChecklistResultView toView(PreventiveChecklistResultEntity entity, List<PreventiveChecklistItem> itemValues) {
    return new ChecklistResultView(entity.getId(), entity.getScheduleId(), entity.getPerformedBy(),
        entity.getCompletedAt(), entity.getNotes(), entity.getLeaderId(), entity.getAssessment(),
        entity.getApprovedAt(), entity.getSignatureObjectKey(), entity.getSignerIdentity(), itemValues);
  }

  // -------------------------------------------------------------------------
  // Commands, views & exceptions
  // -------------------------------------------------------------------------

  public record ItemCommand(String label, String value, BigDecimal lsl, BigDecimal usl, String note) {
  }

  public record ChecklistCommand(String notes, List<ItemCommand> items) {
  }

  public record ApproveCommand(String signatureObjectKey, String signerIdentity, String assessment) {
  }

  public record ChecklistResultView(UUID id, UUID scheduleId, UUID performedBy, Instant completedAt, String notes,
      UUID leaderId, String assessment, Instant approvedAt, String signatureObjectKey, String signerIdentity,
      List<PreventiveChecklistItem> items) {
  }

  public record ChecklistView(ChecklistStatus status, ChecklistResultView result) {
  }

  public static class ScheduleNotFoundException extends RuntimeException {
  }

  public static class ChecklistMachineNotFoundException extends RuntimeException {
  }

  public static class ChecklistProgramNotFoundException extends RuntimeException {
  }

  public static class ChecklistForbiddenException extends RuntimeException {
  }

  public static class ChecklistAlreadySubmittedException extends RuntimeException {
  }

  public static class ChecklistNotSubmittedException extends RuntimeException {
  }

  public static class InvalidStateTransitionException extends RuntimeException {
  }

  public static class MissingSignatureException extends RuntimeException {
  }

  public static class ChecklistValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public ChecklistValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}
