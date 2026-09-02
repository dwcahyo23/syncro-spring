package com.syncro.maintenance.preventive.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.maintenance.preventive.application.PmFrequencyService.PmFrequencyNotFoundException;
import com.syncro.maintenance.preventive.infrastructure.db.ActiveChecksheetEntity;
import com.syncro.maintenance.preventive.infrastructure.db.ActiveChecksheetId;
import com.syncro.maintenance.preventive.infrastructure.db.ActiveChecksheetRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmChecksheetEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmChecksheetRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmFrequencyEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmFrequencyRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PM checksheet revision/approval workflow service (story 19-1, blueprint F2/F3).
 * Create revision 1 (unapproved), revise (supersedes self-FK, next revision_no),
 * approve (stamps approver/time/effective_date, flips the active_checksheets
 * pointer, deactivates the old revision).
 *
 * <p>Gates: create/revise by STAFF_MAINTENANCE/SECTION_LEADER/MAINTENANCE_LEADER/
 * MANAGER_MAINTENANCE (SUPER_ADMIN bypass). Approve additionally requires a leader
 * role (SECTION_LEADER/MAINTENANCE_LEADER/MANAGER_MAINTENANCE/SUPER_ADMIN).
 * Machine-scoped checksheet actions use the requireMutationAccess pattern (plant/
 * group scope via OperationalScopeService); reads are scope-filtered the same way
 * {@link PreventiveProgramService#list} filters (scoped data never leaks across
 * plant/group boundaries).
 *
 * <p>Workflow guards: only the latest revision of a (machine, frequency) pair may
 * be revised or approved — approving a stale revision would regress the active
 * pointer, and revising a superseded revision would fork the supersedes chain.
 * Both surface as 409 INVALID_CHECKSHEET_TRANSITION.
 */
@Service
public class PmChecksheetService {

  private static final String CHECKSHEET_REVISION_UNIQUE_CONSTRAINT =
      "uq_pm_checksheets_machine_frequency_revision";

  private final PmChecksheetRepository checksheets;
  private final PmFrequencyRepository frequencies;
  private final MachineRepository machines;
  private final ActiveChecksheetRepository activeChecksheets;
  private final AuditLogWriter auditLog;
  private final OperationalScopeService scopes;
  private final Clock clock;

  public PmChecksheetService(PmChecksheetRepository checksheets, PmFrequencyRepository frequencies,
      MachineRepository machines, ActiveChecksheetRepository activeChecksheets,
      AuditLogWriter auditLog, OperationalScopeService scopes, Clock clock) {
    this.checksheets = checksheets;
    this.frequencies = frequencies;
    this.machines = machines;
    this.activeChecksheets = activeChecksheets;
    this.auditLog = auditLog;
    this.scopes = scopes;
    this.clock = clock;
  }

  // -------------------------------------------------------------------------
  // Create (revision 1)
  // -------------------------------------------------------------------------

  @Transactional
  public ChecksheetView create(AuthenticatedUser user, CreateChecksheetCommand command) {
    var machine = loadMachine(command.machineId());
    requireMutationAccess(user, machine);
    var frequency = loadFrequency(command.frequencyId());
    if (!frequency.isActive()) {
      throw new ChecksheetValidationException(
          Map.of("frequencyId", "Frequency is not active."));
    }
    if (checksheets.existsByMachineIdAndFrequencyId(machine.getId(), frequency.getId())) {
      throw new ChecksheetAlreadyExistsException();
    }
    var now = Instant.now(clock);
    var entity = new PmChecksheetEntity(
        UUID.randomUUID(), machine.getId(), frequency.getId(), 1,
        normalizeOptional(command.revisionReason()), false, null, null, null, null,
        UUID.fromString(user.id()), now, now);
    var saved = save(entity);
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.PM_CHECKSHEET,
        saved.getId(), label(saved, machine), machine.getPlant().getId(), null,
        checksheetValues(saved), null));
    return toView(saved);
  }

  // -------------------------------------------------------------------------
  // List / Get / Active (scope-filtered reads)
  // -------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<ChecksheetView> list(AuthenticatedUser user, UUID machineId, UUID frequencyId) {
    var scope = scopes.derive(user);
    List<PmChecksheetEntity> rows;
    if (machineId != null && frequencyId != null) {
      rows = checksheets.findByMachineIdAndFrequencyIdOrderByRevisionNoAsc(machineId, frequencyId);
    } else if (machineId != null) {
      rows = checksheets.findByMachineIdOrderByRevisionNoDesc(machineId);
    } else if (frequencyId != null) {
      rows = checksheets.findByFrequencyIdOrderByRevisionNoDesc(frequencyId);
    } else {
      // Unfiltered: pin a stable order (findAll() alone is arbitrary DB order).
      rows = checksheets.findAll().stream()
          .sorted(Comparator.comparing(PmChecksheetEntity::getMachineId)
              .thenComparing(PmChecksheetEntity::getFrequencyId)
              .thenComparing(PmChecksheetEntity::getRevisionNo))
          .toList();
    }
    // PreventiveProgramService.list idiom: SUPER_ADMIN (plantIds == null) is
    // unrestricted; everyone else only sees checksheets whose machine is in scope.
    if (scope.plantIds() == null) {
      return rows.stream().map(PmChecksheetService::toView).toList();
    }
    var result = new ArrayList<ChecksheetView>();
    for (var checksheet : rows) {
      var machine = machines.findByIdWithPlantAndGroup(checksheet.getMachineId());
      if (machine.isPresent() && inScope(scope, machine.get())) {
        result.add(toView(checksheet));
      }
    }
    return result;
  }

  @Transactional(readOnly = true)
  public ChecksheetView get(AuthenticatedUser user, UUID id) {
    var entity = loadChecksheet(id);
    requireReadAccess(user, entity.getMachineId());
    return toView(entity);
  }

  @Transactional(readOnly = true)
  public ActiveChecksheetView getActive(AuthenticatedUser user, UUID machineId, UUID frequencyId) {
    requireReadAccess(user, machineId);
    var pointer = activeChecksheets
        .findByIdMachineIdAndIdFrequencyId(machineId, frequencyId)
        .orElseThrow(ActiveChecksheetNotFoundException::new);
    var checksheet = checksheets.findById(pointer.getChecksheetId())
        .orElseThrow(PmChecksheetNotFoundException::new);
    return new ActiveChecksheetView(checksheet.getId(), machineId, frequencyId,
        checksheet.getRevisionNo());
  }

  // -------------------------------------------------------------------------
  // Revise
  // -------------------------------------------------------------------------

  @Transactional
  public ChecksheetView revise(AuthenticatedUser user, UUID id, ReviseChecksheetCommand command) {
    var source = loadChecksheet(id);
    var machine = loadMachine(source.getMachineId());
    requireMutationAccess(user, machine);
    // Same precondition as create: a revision may only extend an active frequency.
    var frequency = loadFrequency(source.getFrequencyId());
    if (!frequency.isActive()) {
      throw new ChecksheetValidationException(
          Map.of("frequencyId", "Frequency is not active."));
    }
    // Only the latest revision may be revised — a superseded source would fork
    // the chain (two revisions claiming the same predecessor).
    var maxRevision = checksheets.findMaxRevisionNo(machine.getId(), source.getFrequencyId())
        .orElse(0);
    if (source.getRevisionNo() < maxRevision) {
      throw new InvalidChecksheetTransitionException();
    }
    var now = Instant.now(clock);
    var entity = new PmChecksheetEntity(
        UUID.randomUUID(), machine.getId(), source.getFrequencyId(), maxRevision + 1,
        normalizeOptional(command.revisionReason()), false, source.getId(), null, null, null,
        UUID.fromString(user.id()), now, now);
    var saved = save(entity);
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.PM_CHECKSHEET,
        saved.getId(), label(saved, machine), machine.getPlant().getId(), null,
        checksheetValues(saved), null));
    return toView(saved);
  }

  // -------------------------------------------------------------------------
  // Approve
  // -------------------------------------------------------------------------

  @Transactional
  public ChecksheetView approve(AuthenticatedUser user, UUID id, ApproveChecksheetCommand command) {
    var entity = loadChecksheet(id);
    requireApproveRole(user);
    if (entity.getApprovedBy() != null) {
      throw new InvalidChecksheetTransitionException();
    }
    var machine = loadMachine(entity.getMachineId());
    requireMutationAccess(user, machine);
    // Only the latest revision may be approved — approving a stale revision would
    // regress the active pointer to superseded content.
    var maxRevision = checksheets.findMaxRevisionNo(machine.getId(), entity.getFrequencyId())
        .orElse(0);
    if (entity.getRevisionNo() < maxRevision) {
      throw new InvalidChecksheetTransitionException();
    }
    var now = Instant.now(clock);
    var effectiveDate = command.effectiveDate() != null ? command.effectiveDate() : LocalDate.now(clock);
    var previous = checksheetValues(entity);

    // Deactivate the old active revision for this (machine, frequency) pair —
    // audit-logged as its own UPDATE so the pointer flip is fully traceable.
    activeChecksheets.findByIdMachineIdAndIdFrequencyId(machine.getId(), entity.getFrequencyId())
        .ifPresent(oldPtr -> {
          var oldActive = checksheets.findById(oldPtr.getChecksheetId()).orElse(null);
          if (oldActive != null && !oldActive.getId().equals(entity.getId())) {
            var oldPrevious = checksheetValues(oldActive);
            oldActive.deactivate(now);
            checksheets.save(oldActive);
            auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_CHECKSHEET,
                oldActive.getId(), label(oldActive, machine), machine.getPlant().getId(),
                oldPrevious, checksheetValues(oldActive), null));
          }
        });

    // Stamp the new revision as approved and active.
    entity.approve(UUID.fromString(user.id()), now, effectiveDate, now);
    entity.activate(now);
    var saved = checksheets.saveAndFlush(entity);

    // Upsert the active_checksheets pointer (composite PK makes this find-then-save).
    var pointerId = new ActiveChecksheetId(machine.getId(), entity.getFrequencyId());
    var pointer = activeChecksheets.findById(pointerId).orElse(null);
    if (pointer != null) {
      pointer.setChecksheetId(saved.getId());
    } else {
      pointer = new ActiveChecksheetEntity(pointerId, saved.getId());
    }
    try {
      activeChecksheets.saveAndFlush(pointer);
    } catch (DataIntegrityViolationException exception) {
      // uq_active_checksheets_checksheet: a concurrent approve raced us.
      throw new InvalidChecksheetTransitionException();
    }

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_CHECKSHEET,
        saved.getId(), label(saved, machine), machine.getPlant().getId(), previous,
        checksheetValues(saved), null));
    return toView(saved);
  }

  // -------------------------------------------------------------------------
  // Gates
  // -------------------------------------------------------------------------

  /** Gate: create/revise/approve by STAFF_MAINTENANCE+; scope via requireMutationAccess. */
  private void requireMutationAccess(AuthenticatedUser user, MachineEntity machine) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    var scope = scopes.derive(user);
    switch (user.applicationRole()) {
      case SECTION_LEADER -> {
        if (!groupInScope(scope, machine)) {
          throw new PmChecksheetForbiddenException();
        }
      }
      case MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {
        if (!plantInScope(scope, machine) && !groupInScope(scope, machine)) {
          throw new PmChecksheetForbiddenException();
        }
      }
      case STAFF_MAINTENANCE -> {
        if (!plantInScope(scope, machine)) {
          throw new PmChecksheetForbiddenException();
        }
      }
      default -> throw new PmChecksheetForbiddenException();
    }
  }

  /** Read gate: SUPER_ADMIN unrestricted; everyone else needs the machine in scope. */
  private void requireReadAccess(AuthenticatedUser user, UUID machineId) {
    var machine = loadMachine(machineId);
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    var scope = scopes.derive(user);
    if (!inScope(scope, machine)) {
      throw new PmChecksheetForbiddenException();
    }
  }

  /** Approve additionally requires a leader role. */
  private void requireApproveRole(AuthenticatedUser user) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    switch (user.applicationRole()) {
      case SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {}
      default -> throw new PmChecksheetForbiddenException();
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
  // Loaders
  // -------------------------------------------------------------------------

  private MachineEntity loadMachine(UUID machineId) {
    return machines.findByIdWithPlantAndGroup(machineId)
        .orElseThrow(MachineNotFoundException::new);
  }

  private PmFrequencyEntity loadFrequency(UUID frequencyId) {
    return frequencies.findById(frequencyId)
        .orElseThrow(PmFrequencyNotFoundException::new);
  }

  private PmChecksheetEntity loadChecksheet(UUID id) {
    return checksheets.findById(id).orElseThrow(PmChecksheetNotFoundException::new);
  }

  /**
   * Race backstop: uq_pm_checksheets_machine_frequency_revision → 409. A rev-1
   * collision is a duplicate CREATE (same logical outcome as the pre-check →
   * CHECKSHEET_ALREADY_EXISTS); a higher-rev collision is a revise race →
   * INVALID_CHECKSHEET_TRANSITION.
   */
  private PmChecksheetEntity save(PmChecksheetEntity entity) {
    try {
      return checksheets.saveAndFlush(entity);
    } catch (DataIntegrityViolationException exception) {
      var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
      if (message.contains(CHECKSHEET_REVISION_UNIQUE_CONSTRAINT)) {
        if (entity.getRevisionNo() == 1) {
          throw new ChecksheetAlreadyExistsException();
        }
        throw new InvalidChecksheetTransitionException();
      }
      throw exception;
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String label(PmChecksheetEntity entity, MachineEntity machine) {
    return "c" + entity.getRevisionNo() + " @" + (machine != null ? machine.getCode() : "?");
  }

  private static Map<String, Object> checksheetValues(PmChecksheetEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", entity.getId().toString());
    values.put("machineId", entity.getMachineId().toString());
    values.put("frequencyId", entity.getFrequencyId().toString());
    values.put("revisionNo", entity.getRevisionNo());
    values.put("isActive", entity.isActive());
    values.put("supersedes", entity.getSupersedes() != null ? entity.getSupersedes().toString() : null);
    values.put("approvedBy", entity.getApprovedBy() != null ? entity.getApprovedBy().toString() : null);
    values.put("approvedAt", entity.getApprovedAt() != null ? entity.getApprovedAt().toString() : null);
    values.put("effectiveDate", entity.getEffectiveDate() != null ? entity.getEffectiveDate().toString() : null);
    values.put("createdBy", entity.getCreatedBy() != null ? entity.getCreatedBy().toString() : null);
    return values;
  }

  private static ChecksheetView toView(PmChecksheetEntity entity) {
    return new ChecksheetView(entity.getId(), entity.getMachineId(), entity.getFrequencyId(),
        entity.getRevisionNo(), entity.getRevisionReason(), entity.isActive(),
        entity.getSupersedes(), entity.getApprovedBy(), entity.getApprovedAt(),
        entity.getEffectiveDate(), entity.getCreatedBy(), entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  // -------------------------------------------------------------------------
  // Commands & views
  // -------------------------------------------------------------------------

  public record CreateChecksheetCommand(UUID machineId, UUID frequencyId,
      String revisionReason) {
  }

  public record ReviseChecksheetCommand(String revisionReason) {
  }

  public record ApproveChecksheetCommand(LocalDate effectiveDate) {
  }

  public record ChecksheetView(UUID id, UUID machineId, UUID frequencyId, int revisionNo,
      String revisionReason, boolean isActive, UUID supersedes, UUID approvedBy,
      Instant approvedAt, LocalDate effectiveDate, UUID createdBy, Instant createdAt,
      Instant updatedAt) {
  }

  public record ActiveChecksheetView(UUID checksheetId, UUID machineId, UUID frequencyId,
      int revisionNo) {
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  public static class PmChecksheetNotFoundException extends RuntimeException {
  }

  public static class MachineNotFoundException extends RuntimeException {
  }

  public static class PmChecksheetForbiddenException extends RuntimeException {
  }

  public static class InvalidChecksheetTransitionException extends RuntimeException {
  }

  public static class ActiveChecksheetNotFoundException extends RuntimeException {
  }

  /** (machine, frequency) already has a checksheet — create is revision 1 only. */
  public static class ChecksheetAlreadyExistsException extends RuntimeException {
  }

  public static class ChecksheetValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public ChecksheetValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}
