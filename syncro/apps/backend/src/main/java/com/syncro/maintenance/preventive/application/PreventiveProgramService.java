package com.syncro.maintenance.preventive.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.preventive.domain.PreventiveCategory;
import com.syncro.maintenance.preventive.domain.PreventiveProgram;
import com.syncro.maintenance.preventive.domain.PreventiveSchedule;
import com.syncro.maintenance.preventive.domain.ScheduleStatus;
import com.syncro.maintenance.preventive.domain.ScheduleType;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveProgramEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveProgramRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Preventive programs &amp; schedules (FR-130/FR-131, AD-12, story 11-1). A program
 * defines a per-machine recurring maintenance anchor (MONTHLY/ANNUAL, mechanical/
 * electrical); schedules are materialized due instances generated on the calendar
 * anchor from the server clock and work without telemetry. The next due date rolls
 * forward from completion (floating interval).
 */
@Service
public class PreventiveProgramService {

  private static final int WINDOW_MONTHS = 12;

  private final PreventiveProgramRepository programs;
  private final PreventiveScheduleRepository schedules;
  private final MachineRepository machines;
  private final AuditLogWriter auditLog;
  private final OperationalScopeService scopes;
  private final Clock clock;

  public PreventiveProgramService(PreventiveProgramRepository programs, PreventiveScheduleRepository schedules,
      MachineRepository machines, AuditLogWriter auditLog, OperationalScopeService scopes, Clock clock) {
    this.programs = programs;
    this.schedules = schedules;
    this.machines = machines;
    this.auditLog = auditLog;
    this.scopes = scopes;
    this.clock = clock;
  }

  // -------------------------------------------------------------------------
  // Program CRUD
  // -------------------------------------------------------------------------

  @Transactional
  public PreventiveProgram create(AuthenticatedUser user, CreateProgramCommand command) {
    var machine = loadMachine(command.machineId());
    requireMutationAccess(user, machine);
    validate(command.category(), command.scheduleType(), command.dayOfMonth(), command.monthOfYear(),
        command.title());

    var now = Instant.now(clock);
    var entity = new PreventiveProgramEntity(UUID.randomUUID(), machine.getId(), command.category(),
        command.scheduleType(), (short) command.dayOfMonth(), command.monthOfYear() != null ? command.monthOfYear().shortValue() : null, command.title().trim(),
        normalize(command.description()), true, UUID.fromString(user.id()), now, now);
    var saved = programs.saveAndFlush(entity);
    generateWindow(saved);

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.PREVENTIVE_PROGRAM,
        saved.getId(), saved.getTitle(), machine.getPlant().getId(), null, programValues(saved), null));
    return PreventiveMapper.toDomain(saved);
  }

  @Transactional(readOnly = true)
  public List<PreventiveProgram> list(AuthenticatedUser user) {
    var scope = scopes.derive(user);
    var all = programs.findAllByOrderByCreatedAtDesc();
    if (scope.plantIds() == null) {
      return all.stream().map(PreventiveMapper::toDomain).toList();
    }
    var result = new ArrayList<PreventiveProgram>();
    for (var program : all) {
      var machine = machines.findByIdWithPlantAndGroup(program.getMachineId());
      if (machine.isPresent() && inScope(scope, machine.get())) {
        result.add(PreventiveMapper.toDomain(program));
      }
    }
    return result;
  }

  @Transactional(readOnly = true)
  public PreventiveProgram get(String id) {
    return PreventiveMapper.toDomain(loadProgram(id));
  }

  @Transactional
  public PreventiveProgram update(AuthenticatedUser user, String id, UpdateProgramCommand command) {
    var entity = loadProgram(id);
    var machine = loadMachine(entity.getMachineId());
    requireMutationAccess(user, machine);
    validate(entity.getCategory(), entity.getScheduleType(), command.dayOfMonth(), command.monthOfYear(),
        command.title());

    entity.update(command.title().trim(), normalize(command.description()), command.active(),
        (short) command.dayOfMonth(), command.monthOfYear() != null ? command.monthOfYear().shortValue() : null,
        Instant.now(clock));
    var saved = programs.saveAndFlush(entity);
    generateWindow(saved);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PREVENTIVE_PROGRAM,
        saved.getId(), saved.getTitle(), machine.getPlant().getId(), null, programValues(saved), null));
    return PreventiveMapper.toDomain(saved);
  }

  @Transactional
  public void delete(AuthenticatedUser user, String id) {
    var entity = loadProgram(id);
    var machine = loadMachine(entity.getMachineId());
    requireMutationAccess(user, machine);
    programs.delete(entity);

    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.PREVENTIVE_PROGRAM,
        entity.getId(), entity.getTitle(), machine.getPlant().getId(), programValues(entity), null, null));
  }
  // ponytail: Garage objects (attachments + signature) orphaned on program/schedule
  // cascade delete — same gap as the workorder module (story 10-5). Revisit with a
  // bucket-lifecycle policy or a scheduled cleanup job if storage cost matters.

  // -------------------------------------------------------------------------
  // Schedule generation (FR-131, AD-12)
  // -------------------------------------------------------------------------

  /** Regenerates a program's schedule window idempotently (ON CONFLICT DO NOTHING). */
  @Transactional
  public List<PreventiveSchedule> generate(AuthenticatedUser user, String id) {
    var entity = loadProgram(id);
    var machine = loadMachine(entity.getMachineId());
    requireMutationAccess(user, machine);
    return generateWindow(entity);
  }

  /** Rolls the floating interval forward: materializes the first anchor after the completion date. */
  @Transactional
  public PreventiveSchedule rollForwardNext(UUID programId, Instant completedAt) {
    var entity = programs.findById(programId).orElseThrow(ProgramNotFoundException::new);
    var fromDate = LocalDate.ofInstant(completedAt, clock.getZone());
    var anchor = nextAnchor(entity, fromDate);
    return materialize(entity, anchor, fromDate);
  }

  private List<PreventiveSchedule> generateWindow(PreventiveProgramEntity program) {
    var today = LocalDate.now(clock);
    var from = today.minusDays(1); // window starts from today inclusive
    var generated = new ArrayList<PreventiveSchedule>();
    var anchor = nextAnchor(program, from);
    for (int i = 0; i < WINDOW_MONTHS; i++) {
      generated.add(materialize(program, anchor, today));
      anchor = nextAnchor(program, anchor.plusDays(1));
    }
    return generated;
  }

  private PreventiveSchedule materialize(PreventiveProgramEntity program, LocalDate dueDate, LocalDate fromDate) {
    if (schedules.existsByProgramIdAndDueDate(program.getId(), dueDate)) {
      var existing = schedules.findByProgramIdOrderByDueDateAsc(program.getId()).stream()
          .filter(s -> s.getDueDate().equals(dueDate)).findFirst().orElse(null);
      return existing == null ? null : PreventiveMapper.toDomain(existing);
    }
    var now = Instant.now(clock);
    var entity = new PreventiveScheduleEntity(UUID.randomUUID(), program.getId(), program.getMachineId(), dueDate,
        ScheduleStatus.SCHEDULED, null, null, now, now);
    var saved = schedules.saveAndFlush(entity);
    auditLog.recordSystem(new AuditRecord(AuditAction.CREATE, AuditEntityType.PREVENTIVE_SCHEDULE,
        saved.getId(), "due " + dueDate, null, null, scheduleValues(saved), null));
    return PreventiveMapper.toDomain(saved);
  }

  /**
   * First anchor on/after {@code fromDate}. MONTHLY: (y, m, min(dayOfMonth, daysInMonth))
   * advancing month by month. ANNUAL: (y, monthOfYear, min(dayOfMonth, daysInMonth))
   * advancing year by year. Clamping handles Feb 29/30/31 and short months.
   */
  public static LocalDate nextAnchor(PreventiveProgramEntity program, LocalDate fromDate) {
    if (program.getScheduleType() == ScheduleType.ANNUAL) {
      var month = program.getMonthOfYear() == null ? 1 : program.getMonthOfYear();
      var year = fromDate.getYear();
      var candidate = clamp(YearMonth.of(year, month), program.getDayOfMonth());
      while (candidate.isBefore(fromDate)) {
        year += 1;
        candidate = clamp(YearMonth.of(year, month), program.getDayOfMonth());
      }
      return candidate;
    }
    var year = fromDate.getYear();
    var month = fromDate.getMonthValue();
    var candidate = clamp(YearMonth.of(year, month), program.getDayOfMonth());
    while (candidate.isBefore(fromDate)) {
      var next = YearMonth.of(year, month).plusMonths(1);
      year = next.getYear();
      month = next.getMonthValue();
      candidate = clamp(next, program.getDayOfMonth());
    }
    return candidate;
  }

  private static LocalDate clamp(YearMonth yearMonth, int dayOfMonth) {
    return yearMonth.atDay(Math.min(dayOfMonth, yearMonth.lengthOfMonth()));
  }

  // -------------------------------------------------------------------------
  // Validation & gates
  // -------------------------------------------------------------------------

  private void validate(PreventiveCategory category, ScheduleType scheduleType, int dayOfMonth,
      Integer monthOfYear, String title) {
    var fieldErrors = new LinkedHashMap<String, String>();
    if (category == null) {
      fieldErrors.put("category", "Category must be MECHANICAL or ELECTRICAL.");
    }
    if (scheduleType == null) {
      fieldErrors.put("scheduleType", "Schedule type must be MONTHLY or ANNUAL.");
    } else if (scheduleType == ScheduleType.ANNUAL && monthOfYear == null) {
      fieldErrors.put("monthOfYear", "Month of year is required for ANNUAL schedules.");
    } else if (scheduleType == ScheduleType.MONTHLY && monthOfYear != null) {
      fieldErrors.put("monthOfYear", "Month of year must be null for MONTHLY schedules.");
    }
    if (dayOfMonth < 1 || dayOfMonth > 31) {
      fieldErrors.put("dayOfMonth", "Day of month must be between 1 and 31.");
    }
    if (monthOfYear != null && (monthOfYear < 1 || monthOfYear > 12)) {
      fieldErrors.put("monthOfYear", "Month of year must be between 1 and 12.");
    }
    var normalizedTitle = title == null ? "" : title.trim();
    if (normalizedTitle.isEmpty()) {
      fieldErrors.put("title", "Title must not be blank.");
    } else if (normalizedTitle.length() > 200) {
      fieldErrors.put("title", "Title must be at most 200 characters.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new PreventiveValidationException(fieldErrors);
    }
  }

  /** Gate: SUPER_ADMIN exempt; SECTION_LEADER needs group scope; leader roles need plant or group; STAFF needs plant. */
  private void requireMutationAccess(AuthenticatedUser user, MachineEntity machine) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    var scope = scopes.derive(user);
    switch (user.applicationRole()) {
      case SECTION_LEADER -> {
        if (!groupInScope(scope, machine)) {
          throw new PreventiveForbiddenException();
        }
      }
      case MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {
        if (!plantInScope(scope, machine) && !groupInScope(scope, machine)) {
          throw new PreventiveForbiddenException();
        }
      }
      case STAFF_MAINTENANCE -> {
        if (!plantInScope(scope, machine)) {
          throw new PreventiveForbiddenException();
        }
      }
      default -> throw new PreventiveForbiddenException();
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

  private MachineEntity loadMachine(UUID machineId) {
    return machines.findByIdWithPlantAndGroup(machineId).orElseThrow(MachineNotFoundException::new);
  }

  private PreventiveProgramEntity loadProgram(String id) {
    return programs.findById(UUID.fromString(id)).orElseThrow(ProgramNotFoundException::new);
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private Map<String, Object> programValues(PreventiveProgramEntity program) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", program.getId());
    values.put("machineId", program.getMachineId());
    values.put("category", program.getCategory().name());
    values.put("scheduleType", program.getScheduleType().name());
    values.put("dayOfMonth", program.getDayOfMonth());
    values.put("monthOfYear", program.getMonthOfYear());
    values.put("title", program.getTitle());
    values.put("active", program.isActive());
    return values;
  }

  private Map<String, Object> scheduleValues(PreventiveScheduleEntity schedule) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", schedule.getId());
    values.put("programId", schedule.getProgramId());
    values.put("machineId", schedule.getMachineId());
    values.put("dueDate", schedule.getDueDate().toString());
    values.put("status", schedule.getStatus().name());
    return values;
  }

  // -------------------------------------------------------------------------
  // Commands & exceptions
  // -------------------------------------------------------------------------

  public record CreateProgramCommand(UUID machineId, PreventiveCategory category, ScheduleType scheduleType,
      int dayOfMonth, Integer monthOfYear, String title, String description) {
  }

  public record UpdateProgramCommand(int dayOfMonth, Integer monthOfYear, String title, String description,
      boolean active) {
  }

  public static class MachineNotFoundException extends RuntimeException {
  }

  public static class ProgramNotFoundException extends RuntimeException {
  }

  public static class PreventiveForbiddenException extends RuntimeException {
  }

  public static class PreventiveValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public PreventiveValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}