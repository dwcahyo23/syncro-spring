package com.syncro.maintenance.preventive.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.maintenance.preventive.domain.PmScheduleDateStatus;
import com.syncro.maintenance.preventive.domain.PmScheduleStatus;
import com.syncro.maintenance.preventive.infrastructure.db.ActiveChecksheetRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmChecksheetEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmChecksheetRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmFrequencyEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmFrequencyRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmScheduleDateEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmScheduleDateRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmScheduleEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmScheduleRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PM yearly schedule + schedule-date service (story 19-3, blueprint F5). Creates a
 * yearly plan that snapshots an approved checksheet (revision, frequency code/name)
 * and materializes the year's {@code pm_schedule_dates} from the checksheet's
 * {@code effective_date} anchor (or the current date when the revision has none — the
 * earliest an approved revision can anchor a plan). Frequencies MONTHLY (one clamped
 * date per month of the target year) and ANNUAL (one date at the anchor month/day in
 * the target year) only — matching the V7 MONTHLY/ANNUAL seed and the F5 calendar
 * model.
 *
 * <p>Approvals are strictly sequential: DRAFT → PENDING_SPV_APPROVAL (submit) →
 * PENDING_PRODUCTION_APPROVAL (approve-spv) → APPROVED (approve-prod) → ACTIVE
 * (activate). Each step stamps its actor/timestamp column (submitted_by/at,
 * approved_by_spv/at, approved_by_prod/at) and writes an audit UPDATE with the
 * previous/new status. Only ACTIVE schedules expose the per-date transition endpoint;
 * date statuses are repeatable (a RESCHEDULED date can later be EXECUTED) — no
 * terminal per-date state.
 *
 * <p>Gates: create/submit by STAFF_MAINTENANCE/SECTION_LEADER/MAINTENANCE_LEADER/
 * MANAGER_MAINTENANCE (SUPER_ADMIN bypass); approve-spv/approve-prod/activate require
 * a leader role (SECTION_LEADER/MAINTENANCE_LEADER/MANAGER_MAINTENANCE/SUPER_ADMIN).
 * Machine plant/group scope resolves through the machine (19-1 requireMutationAccess
 * pattern); reads are scope-filtered so scoped data never leaks across plant/group
 * boundaries.
 *
 * <p>Concurrency: every status-change path loads the schedule row through
 * {@code findByIdForUpdate} with PESSIMISTIC_WRITE (18-5 pattern), serializing
 * concurrent transitions so exactly one caller wins and the loser sees the committed
 * state and throws.
 */
@Service
public class PmScheduleService {

  private static final String SCHEDULE_UNIQUE_CONSTRAINT =
      "uq_pm_schedules_plant_machine_checksheet_year";
  private static final String DATE_UNIQUE_CONSTRAINT =
      "uq_pm_schedule_dates_schedule_planned_date";

  private static final String FREQUENCY_CODE_MONTHLY = "MONTHLY";
  private static final String FREQUENCY_CODE_ANNUAL = "ANNUAL";

  private final PmScheduleRepository schedules;
  private final PmScheduleDateRepository dates;
  private final PmChecksheetRepository checksheets;
  private final PmFrequencyRepository frequencies;
  private final MachineRepository machines;
  private final ActiveChecksheetRepository activeChecksheets;
  private final PlantRepository plants;
  private final AuditLogWriter auditLog;
  private final OperationalScopeService scopes;
  private final Clock clock;

  public PmScheduleService(PmScheduleRepository schedules, PmScheduleDateRepository dates,
      PmChecksheetRepository checksheets, PmFrequencyRepository frequencies,
      MachineRepository machines, ActiveChecksheetRepository activeChecksheets,
      PlantRepository plants, AuditLogWriter auditLog, OperationalScopeService scopes,
      Clock clock) {
    this.schedules = schedules;
    this.dates = dates;
    this.checksheets = checksheets;
    this.frequencies = frequencies;
    this.machines = machines;
    this.activeChecksheets = activeChecksheets;
    this.plants = plants;
    this.auditLog = auditLog;
    this.scopes = scopes;
    this.clock = clock;
  }

  // -------------------------------------------------------------------------
  // Create (schedule + date materialization)
  // -------------------------------------------------------------------------

  @Transactional
  public ScheduleView create(AuthenticatedUser user, CreateScheduleCommand command) {
    if (!plants.existsById(command.plantId())) {
      throw new PlantNotFoundException();
    }
    var machine = loadMachine(command.machineId());
    requireMutationAccess(user, machine);
    if (!machine.getPlant().getId().equals(command.plantId())) {
      throw new ScheduleValidationException(Map.of("machineId", "Machine does not belong to this plant."));
    }
    var checksheet = loadChecksheet(command.checksheetId());
    if (!checksheet.getMachineId().equals(command.machineId())) {
      throw new ScheduleValidationException(
          Map.of("checksheetId", "Checksheet does not belong to this machine."));
    }
    if (checksheet.getApprovedBy() == null) {
      throw new InvalidChecksheetStateException();
    }
    var frequency = loadFrequency(checksheet.getFrequencyId());
    if (!frequency.isActive()) {
      throw new ScheduleValidationException(Map.of("frequencyId", "Frequency is not active."));
    }
    requireActivePointer(machine, checksheet);
    if (schedules.findByPlantIdAndMachineIdAndChecksheetIdAndYear(
        command.plantId(), machine.getId(), checksheet.getId(), command.year()).isPresent()) {
      throw new ScheduleAlreadyExistsException();
    }
    var now = Instant.now(clock);
    var entity = new PmScheduleEntity(
        UUID.randomUUID(), machine.getPlant().getId(), machine.getId(),
        checksheet.getId(), checksheet.getRevisionNo(), checksheet.getFrequencyId(),
        frequency.getCode(), frequency.getName(), command.year(), PmScheduleStatus.DRAFT,
        null, null, null, null, null, null, null, now, now);
    var saved = saveSchedule(entity);
    var materialized = materializeDates(saved, checksheet);
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.PM_SCHEDULE,
        saved.getId(), label(saved, machine), machine.getPlant().getId(), null,
        scheduleValues(saved), null));
    return toView(saved, materialized);
  }

  // -------------------------------------------------------------------------
  // List / Get (scope-filtered reads)
  // -------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<ScheduleView> list(AuthenticatedUser user, Integer year, PmScheduleStatus status) {
    var scope = scopes.derive(user);
    var rows = schedules.findAll().stream()
        .filter(s -> year == null || s.getYear() == year)
        .filter(s -> status == null || s.getStatus() == status)
        .sorted(java.util.Comparator.comparing(PmScheduleEntity::getCreatedAt).reversed()
            .thenComparing(PmScheduleEntity::getId))
        .toList();
    if (scope.plantIds() == null) {
      return rows.stream().map(s -> toView(s, dates.findByScheduleIdOrderByPlannedDateAsc(s.getId())))
          .toList();
    }
    var result = new ArrayList<ScheduleView>();
    for (var schedule : rows) {
      var machine = machines.findByIdWithPlantAndGroup(schedule.getMachineId());
      if (machine.isPresent() && inScope(scope, machine.get())) {
        result.add(toView(schedule, dates.findByScheduleIdOrderByPlannedDateAsc(schedule.getId())));
      }
    }
    return result;
  }

  @Transactional(readOnly = true)
  public ScheduleView get(AuthenticatedUser user, UUID id) {
    var entity = loadSchedule(id);
    var machine = loadMachine(entity.getMachineId());
    if (!canRead(user, machine)) {
      throw new PmScheduleForbiddenException();
    }
    return toView(entity, dates.findByScheduleIdOrderByPlannedDateAsc(entity.getId()));
  }

  // -------------------------------------------------------------------------
  // Approval chain: submit → approve-spv → approve-prod → activate
  //
  // Every transition uses findByIdForUpdate (PESSIMISTIC_WRITE) so concurrent
  // callers serialize on the row lock. The second caller re-reads the committed
  // status after the first commits and fails requireState.
  // -------------------------------------------------------------------------

  @Transactional
  public ScheduleView submit(AuthenticatedUser user, UUID id) {
    var entity = loadForUpdate(id);
    var machine = loadMachine(entity.getMachineId());
    requireMutationAccess(user, machine);
    requireState(entity, PmScheduleStatus.DRAFT);
    var previous = scheduleValues(entity);
    var now = Instant.now(clock);
    entity.markSubmitted(UUID.fromString(user.id()), now, now);
    entity.transitionTo(PmScheduleStatus.PENDING_SPV_APPROVAL, now);
    var saved = schedules.saveAndFlush(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_SCHEDULE,
        saved.getId(), label(saved, machine), machine.getPlant().getId(),
        previous, scheduleValues(saved), null));
    return toView(saved, dates.findByScheduleIdOrderByPlannedDateAsc(saved.getId()));
  }

  @Transactional
  public ScheduleView approveSpv(AuthenticatedUser user, UUID id) {
    var entity = loadForUpdate(id);
    var machine = loadMachine(entity.getMachineId());
    requireLeaderRole(user);
    requireMutationAccess(user, machine);
    requireState(entity, PmScheduleStatus.PENDING_SPV_APPROVAL);
    var previous = scheduleValues(entity);
    var now = Instant.now(clock);
    entity.approveBySpv(UUID.fromString(user.id()), now, now);
    entity.transitionTo(PmScheduleStatus.PENDING_PRODUCTION_APPROVAL, now);
    var saved = schedules.saveAndFlush(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_SCHEDULE,
        saved.getId(), label(saved, machine), machine.getPlant().getId(),
        previous, scheduleValues(saved), null));
    return toView(saved, dates.findByScheduleIdOrderByPlannedDateAsc(saved.getId()));
  }

  @Transactional
  public ScheduleView approveProd(AuthenticatedUser user, UUID id) {
    var entity = loadForUpdate(id);
    var machine = loadMachine(entity.getMachineId());
    requireLeaderRole(user);
    requireMutationAccess(user, machine);
    requireState(entity, PmScheduleStatus.PENDING_PRODUCTION_APPROVAL);
    var previous = scheduleValues(entity);
    var now = Instant.now(clock);
    entity.approveByProd(UUID.fromString(user.id()), now, now);
    entity.transitionTo(PmScheduleStatus.APPROVED, now);
    var saved = schedules.saveAndFlush(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_SCHEDULE,
        saved.getId(), label(saved, machine), machine.getPlant().getId(),
        previous, scheduleValues(saved), null));
    return toView(saved, dates.findByScheduleIdOrderByPlannedDateAsc(saved.getId()));
  }

  @Transactional
  public ScheduleView activate(AuthenticatedUser user, UUID id) {
    var entity = loadForUpdate(id);
    var machine = loadMachine(entity.getMachineId());
    requireLeaderRole(user);
    requireMutationAccess(user, machine);
    requireState(entity, PmScheduleStatus.APPROVED);
    var previous = scheduleValues(entity);
    var now = Instant.now(clock);
    entity.transitionTo(PmScheduleStatus.ACTIVE, now);
    var saved = schedules.saveAndFlush(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_SCHEDULE,
        saved.getId(), label(saved, machine), machine.getPlant().getId(),
        previous, scheduleValues(saved), null));
    return toView(saved, dates.findByScheduleIdOrderByPlannedDateAsc(saved.getId()));
  }

  // -------------------------------------------------------------------------
  // Schedule-date transition (only on ACTIVE schedules)
  // -------------------------------------------------------------------------

  @Transactional
  public ScheduleDateView transitionDate(AuthenticatedUser user, UUID scheduleId, UUID dateId,
      PmScheduleDateStatus targetStatus) {
    if (targetStatus == null) {
      throw new ScheduleValidationException(Map.of("status", "status is required."));
    }
    // Lock the schedule row so the ACTIVE precondition serializes.
    var entity = loadForUpdate(scheduleId);
    var machine = loadMachine(entity.getMachineId());
    requireMutationAccess(user, machine);
    if (entity.getStatus() != PmScheduleStatus.ACTIVE) {
      throw new InvalidScheduleDateTransitionException();
    }
    var dateEntity = loadScheduleDate(dateId);
    if (!dateEntity.getScheduleId().equals(scheduleId)) {
      throw new PmScheduleDateNotFoundException();
    }
    requireDateTransition(dateEntity.getStatus(), targetStatus);
    var previous = dateValues(dateEntity);
    dateEntity.transitionTo(targetStatus, Instant.now(clock));
    var saved = dates.saveAndFlush(dateEntity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_SCHEDULE_DATE,
        saved.getId(), dateLabel(saved), machine.getPlant().getId(),
        previous, dateValues(saved), null));
    return toDateView(saved);
  }

  // -------------------------------------------------------------------------
  // Validation helpers
  // -------------------------------------------------------------------------

  private static void requireState(PmScheduleEntity entity, PmScheduleStatus expected) {
    if (entity.getStatus() != expected) {
      throw new InvalidScheduleTransitionException();
    }
  }

  /**
   * Date outcome rule (F5, spec design notes): EXECUTED from SCHEDULED or RESCHEDULED
   * (statuses are repeatable — a rescheduled date can be executed); MISSED only from
   * SCHEDULED; RESCHEDULED from SCHEDULED or EXECUTED. There is no terminal per-date
   * state and no transition back into SCHEDULED.
   */
  private static void requireDateTransition(PmScheduleDateStatus from, PmScheduleDateStatus to) {
    if (to == PmScheduleDateStatus.SCHEDULED) {
      throw new InvalidScheduleDateTransitionException();
    }
    if (to == PmScheduleDateStatus.RESCHEDULED) {
      if (from == PmScheduleDateStatus.EXECUTED || from == PmScheduleDateStatus.SCHEDULED) {
        return;
      }
      throw new InvalidScheduleDateTransitionException();
    }
    if (to == PmScheduleDateStatus.EXECUTED) {
      if (from == PmScheduleDateStatus.SCHEDULED || from == PmScheduleDateStatus.RESCHEDULED) {
        return;
      }
      throw new InvalidScheduleDateTransitionException();
    }
    if (from != PmScheduleDateStatus.SCHEDULED) {
      throw new InvalidScheduleDateTransitionException();
    }
  }

  private void requireActivePointer(MachineEntity machine, PmChecksheetEntity checksheet) {
    var pointer = activeChecksheets
        .findByIdMachineIdAndIdFrequencyId(machine.getId(), checksheet.getFrequencyId())
        .orElseThrow(InvalidChecksheetStateException::new);
    if (!pointer.getChecksheetId().equals(checksheet.getId())) {
      throw new InvalidChecksheetStateException();
    }
  }

  /**
   * Materializes the year's planned dates from the checksheet's anchor — the
   * {@code effective_date} when present, else the current date from the injected
   * clock (the earliest an approved revision can anchor a plan). MONTHLY: one date
   * per month of the target year at {@code min(anchorDay, daysInMonth)} (Feb 29/30/31
   * and short-month clamping — PreventiveProgramService.nextAnchor pattern). ANNUAL:
   * one date at the anchor month/day in the target year. All dates start SCHEDULED.
   * Unsupported frequency codes (neither MONTHLY nor ANNUAL) are rejected.
   */
  private List<PmScheduleDateEntity> materializeDates(PmScheduleEntity schedule,
      PmChecksheetEntity checksheet) {
    var frequency = loadFrequency(checksheet.getFrequencyId());
    var code = frequency.getCode();
    if (!FREQUENCY_CODE_MONTHLY.equals(code) && !FREQUENCY_CODE_ANNUAL.equals(code)) {
      throw new ScheduleValidationException(
          Map.of("frequencyCode", "Unsupported frequency code: " + code));
    }
    var anchor = checksheet.getEffectiveDate() != null
        ? checksheet.getEffectiveDate()
        : LocalDate.now(clock);
    var anchorDay = anchor.getDayOfMonth();
    var targetYear = schedule.getYear();
    List<LocalDate> planned = new ArrayList<>();
    if (FREQUENCY_CODE_MONTHLY.equals(code)) {
      for (var month = 1; month <= 12; month++) {
        planned.add(clamp(YearMonth.of(targetYear, month), anchorDay));
      }
    } else {
      planned.add(clamp(YearMonth.of(targetYear, anchor.getMonthValue()), anchorDay));
    }
    // Duplicate planned_date within a schedule is impossible for MONTHLY (distinct
    // months) but a crafted ANNUAL anchor must still not collide — the V1 unique
    // constraint is the backstop; surface a 400 rather than a 500.
    var now = Instant.now(clock);
    var rows = new ArrayList<PmScheduleDateEntity>();
    for (var plannedDate : planned) {
      var row = new PmScheduleDateEntity(UUID.randomUUID(), schedule.getId(), plannedDate,
          PmScheduleDateStatus.SCHEDULED, now, now);
      rows.add(saveDate(row));
    }
    return rows;
  }

  private static LocalDate clamp(YearMonth yearMonth, int dayOfMonth) {
    return yearMonth.atDay(Math.min(dayOfMonth, yearMonth.lengthOfMonth()));
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

  /** Gate: create/submit by STAFF+ with machine plant/group scope; SUPER_ADMIN bypass. */
  private void requireMutationAccess(AuthenticatedUser user, MachineEntity machine) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    var scope = scopes.derive(user);
    switch (user.applicationRole()) {
      case SECTION_LEADER -> {
        if (!groupInScope(scope, machine)) {
          throw new PmScheduleForbiddenException();
        }
      }
      case MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {
        if (!plantInScope(scope, machine) && !groupInScope(scope, machine)) {
          throw new PmScheduleForbiddenException();
        }
      }
      case STAFF_MAINTENANCE -> {
        if (!plantInScope(scope, machine)) {
          throw new PmScheduleForbiddenException();
        }
      }
      default -> throw new PmScheduleForbiddenException();
    }
  }

  /** Approve-spv/approve-prod/activate require a leader role (19-1 approve parity). */
  private void requireLeaderRole(AuthenticatedUser user) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    switch (user.applicationRole()) {
      case SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {}
      default -> throw new PmScheduleForbiddenException();
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

  private PmChecksheetEntity loadChecksheet(UUID checksheetId) {
    return checksheets.findById(checksheetId).orElseThrow(PmChecksheetNotFoundException::new);
  }

  private PmFrequencyEntity loadFrequency(UUID frequencyId) {
    return frequencies.findById(frequencyId).orElseThrow(PmFrequencyNotFoundException::new);
  }

  /** Read-only load (GET / list / gate pre-checks). */
  private PmScheduleEntity loadSchedule(UUID id) {
    return schedules.findById(id).orElseThrow(PmScheduleNotFoundException::new);
  }

  /**
   * Mutation load with PESSIMISTIC_WRITE row lock (18-5 pattern). Serializes
   * concurrent submit/approve/activate/date-transition so exactly one caller
   * wins the state check.
   */
  private PmScheduleEntity loadForUpdate(UUID id) {
    return schedules.findByIdForUpdate(id).orElseThrow(PmScheduleNotFoundException::new);
  }

  private PmScheduleDateEntity loadScheduleDate(UUID id) {
    return dates.findById(id).orElseThrow(PmScheduleDateNotFoundException::new);
  }

  /** Race backstop: uq_pm_schedules_plant_machine_checksheet_year → 409. */
  private PmScheduleEntity saveSchedule(PmScheduleEntity entity) {
    try {
      return schedules.saveAndFlush(entity);
    } catch (DataIntegrityViolationException exception) {
      var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
      if (message.contains(SCHEDULE_UNIQUE_CONSTRAINT)) {
        throw new ScheduleAlreadyExistsException();
      }
      throw exception;
    }
  }

  /** Backstop for duplicate planned dates within one schedule → 400 (should not happen). */
  private PmScheduleDateEntity saveDate(PmScheduleDateEntity entity) {
    try {
      return dates.saveAndFlush(entity);
    } catch (DataIntegrityViolationException exception) {
      var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
      if (message.contains(DATE_UNIQUE_CONSTRAINT)) {
        throw new ScheduleValidationException(Map.of("plannedDate", "Duplicate planned date."));
      }
      throw exception;
    }
  }

  // -------------------------------------------------------------------------
  // Audit value maps & views (all UUIDs stringified — 19-1 pattern)
  // -------------------------------------------------------------------------

  private static String label(PmScheduleEntity entity, MachineEntity machine) {
    return entity.getYear() + "@" + (machine != null ? machine.getCode() : "?");
  }

  private static String dateLabel(PmScheduleDateEntity entity) {
    return entity.getPlannedDate().toString();
  }

  private static Map<String, Object> scheduleValues(PmScheduleEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", entity.getId().toString());
    values.put("plantId", entity.getPlantId() != null ? entity.getPlantId().toString() : null);
    values.put("machineId", entity.getMachineId().toString());
    values.put("checksheetId", entity.getChecksheetId().toString());
    values.put("checksheetRevisionNo", entity.getChecksheetRevisionNo());
    values.put("frequencyId", entity.getFrequencyId().toString());
    values.put("frequencyCode", entity.getFrequencyCode());
    values.put("frequencyName", entity.getFrequencyName());
    values.put("year", entity.getYear());
    values.put("status", entity.getStatus().name());
    values.put("submittedBy", entity.getSubmittedBy() != null ? entity.getSubmittedBy().toString() : null);
    values.put("submittedAt", entity.getSubmittedAt() != null ? entity.getSubmittedAt().toString() : null);
    values.put("approvedBySpv", entity.getApprovedBySpv() != null ? entity.getApprovedBySpv().toString() : null);
    values.put("approvedAtSpv", entity.getApprovedAtSpv() != null ? entity.getApprovedAtSpv().toString() : null);
    values.put("approvedByProd", entity.getApprovedByProd() != null ? entity.getApprovedByProd().toString() : null);
    values.put("approvedAtProd", entity.getApprovedAtProd() != null ? entity.getApprovedAtProd().toString() : null);
    // Defensive copy of warnings (JSONB Map) so 19-4 mutations on the live
    // entity reference cannot corrupt the serialised audit snapshot.
    var warnings = entity.getWarnings();
    values.put("warnings", warnings != null ? new LinkedHashMap<>(warnings) : null);
    values.put("createdAt", entity.getCreatedAt().toString());
    values.put("updatedAt", entity.getUpdatedAt().toString());
    return values;
  }

  private static Map<String, Object> dateValues(PmScheduleDateEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", entity.getId().toString());
    values.put("scheduleId", entity.getScheduleId().toString());
    values.put("plannedDate", entity.getPlannedDate().toString());
    values.put("status", entity.getStatus().name());
    values.put("createdAt", entity.getCreatedAt().toString());
    values.put("updatedAt", entity.getUpdatedAt().toString());
    return values;
  }

  private static ScheduleView toView(PmScheduleEntity entity, List<PmScheduleDateEntity> rows) {
    return new ScheduleView(entity.getId(), entity.getPlantId(), entity.getMachineId(),
        entity.getChecksheetId(), entity.getChecksheetRevisionNo(), entity.getFrequencyId(),
        entity.getFrequencyCode(), entity.getFrequencyName(), entity.getYear(),
        entity.getStatus(), entity.getSubmittedBy(), entity.getSubmittedAt(),
        entity.getApprovedBySpv(), entity.getApprovedAtSpv(), entity.getApprovedByProd(),
        entity.getApprovedAtProd(), entity.getWarnings(), entity.getCreatedAt(),
        entity.getUpdatedAt(), rows.stream().map(PmScheduleService::toDateView).toList());
  }

  private static ScheduleDateView toDateView(PmScheduleDateEntity entity) {
    return new ScheduleDateView(entity.getId(), entity.getScheduleId(), entity.getPlannedDate(),
        entity.getStatus(), entity.getCreatedAt(), entity.getUpdatedAt());
  }

  // -------------------------------------------------------------------------
  // Commands & views
  // -------------------------------------------------------------------------

  public record CreateScheduleCommand(UUID plantId, UUID machineId, UUID checksheetId, int year) {
  }

  public record ScheduleView(UUID id, UUID plantId, UUID machineId, UUID checksheetId,
      int checksheetRevisionNo, UUID frequencyId, String frequencyCode, String frequencyName,
      int year, PmScheduleStatus status, UUID submittedBy, Instant submittedAt,
      UUID approvedBySpv, Instant approvedAtSpv, UUID approvedByProd, Instant approvedAtProd,
      Map<String, Object> warnings, Instant createdAt, Instant updatedAt,
      List<ScheduleDateView> dates) {
  }

  public record ScheduleDateView(UUID id, UUID scheduleId, LocalDate plannedDate,
      PmScheduleDateStatus status, Instant createdAt, Instant updatedAt) {
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  public static class PmScheduleNotFoundException extends RuntimeException {
  }

  public static class PlantNotFoundException extends RuntimeException {
  }

  public static class PmScheduleDateNotFoundException extends RuntimeException {
  }

  public static class MachineNotFoundException extends RuntimeException {
  }

  public static class PmChecksheetNotFoundException extends RuntimeException {
  }

  public static class PmFrequencyNotFoundException extends RuntimeException {
  }

  public static class PmScheduleForbiddenException extends RuntimeException {
  }

  public static class ScheduleAlreadyExistsException extends RuntimeException {
  }

  /** Unapproved checksheet or not the active pointer's target → 409. */
  public static class InvalidChecksheetStateException extends RuntimeException {
  }

  /** Any out-of-order approval step → 409. */
  public static class InvalidScheduleTransitionException extends RuntimeException {
  }

  /** Invalid date outcome transition (or schedule not ACTIVE) → 409. */
  public static class InvalidScheduleDateTransitionException extends RuntimeException {
  }

  /** Field-level errors (e.g. missing status) → 400 VALIDATION_ERROR. */
  public static class ScheduleValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public ScheduleValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}