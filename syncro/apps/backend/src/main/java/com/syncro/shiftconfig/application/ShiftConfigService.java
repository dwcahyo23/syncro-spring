package com.syncro.shiftconfig.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JobScopeService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.projection.application.ProjectionCacheEvictionEvent;
import com.syncro.shiftconfig.infrastructure.MachineGroupShiftWindowEntity;
import com.syncro.shiftconfig.infrastructure.MachineGroupShiftWindowRepository;
import com.syncro.shiftconfig.infrastructure.MachineShiftWindowEntity;
import com.syncro.shiftconfig.infrastructure.MachineShiftWindowRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shift schedule configuration (Story 8-5). Machine groups own up to three daily wall-clock
 * windows; machines may override them (machine wins). Mutation gate order mirrors the sparepart
 * module: app role (SUPER_ADMIN|MANAGE) first, then LEADER-or-above job scope, then plant access.
 * Reads require only plant access so viewers can see the effective calendar.
 */
@Service
public class ShiftConfigService {
  private static final String JOB_SCOPE_LEVEL = "LEADER";
  private static final int MAX_SHIFTS = 3;
  private static final DateTimeFormatter WIRE_TIME = DateTimeFormatter.ofPattern("HH:mm");
  private static final String SOURCE_MACHINE = "MACHINE";
  private static final String SOURCE_MACHINE_GROUP = "MACHINE_GROUP";
  private static final String SOURCE_NONE = "NONE";

  private final MachineGroupRepository machineGroups;
  private final MachineRepository machines;
  private final MachineGroupShiftWindowRepository groupWindows;
  private final MachineShiftWindowRepository machineWindows;
  private final PlantScopeService plantScopes;
  private final JobScopeService jobScopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  public ShiftConfigService(MachineGroupRepository machineGroups, MachineRepository machines,
      MachineGroupShiftWindowRepository groupWindows, MachineShiftWindowRepository machineWindows,
      PlantScopeService plantScopes, JobScopeService jobScopes, AuditLogWriter auditLog, Clock clock,
      ApplicationEventPublisher events) {
    this.machineGroups = machineGroups;
    this.machines = machines;
    this.groupWindows = groupWindows;
    this.machineWindows = machineWindows;
    this.plantScopes = plantScopes;
    this.jobScopes = jobScopes;
    this.auditLog = auditLog;
    this.clock = clock;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public MachineGroupShiftConfigView getGroupConfig(AuthenticatedUser user, UUID machineGroupId) {
    var group = findScopedGroup(user, machineGroupId);
    return toGroupView(storedGroupShifts(groupWindows.findAllByMachineGroupIdOrderByShiftNumber(group.getId())));
  }

  @Transactional
  public MachineGroupShiftConfigView setGroupConfig(AuthenticatedUser user, UUID machineGroupId,
      List<ShiftWindowCommand> shifts) {
    requireMutationRole(user);
    jobScopes.requireLevelOrAbove(user, JOB_SCOPE_LEVEL);
    var group = findScopedGroup(user, machineGroupId);
    var normalized = validateShifts(shifts);
    var previous = storedGroupShifts(groupWindows.findAllByMachineGroupIdOrderByShiftNumber(group.getId()));
    if (normalized.isEmpty()) {
      if (!previous.isEmpty()) {
        groupWindows.deleteByMachineGroupId(group.getId());
        auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.MACHINE_GROUP, group.getId(),
            group.getName(), group.getPlant().getId(), snapshot(previous), null, null));
        events.publishEvent(ProjectionCacheEvictionEvent.all());
      }
      return new MachineGroupShiftConfigView(List.of());
    }
    if (previous.isEmpty()) {
      var saved = insertGroupWindows(group, normalized);
      auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.MACHINE_GROUP, group.getId(),
          group.getName(), group.getPlant().getId(), null, snapshot(storedGroupShifts(saved)), null));
      events.publishEvent(ProjectionCacheEvictionEvent.all());
      return toGroupView(storedGroupShifts(saved));
    }
    groupWindows.deleteByMachineGroupId(group.getId());
    var saved = insertGroupWindows(group, normalized);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.MACHINE_GROUP, group.getId(),
        group.getName(), group.getPlant().getId(), snapshot(previous), snapshot(storedGroupShifts(saved)), null));
    events.publishEvent(ProjectionCacheEvictionEvent.all());
    return toGroupView(storedGroupShifts(saved));
  }

  @Transactional(readOnly = true)
  public MachineShiftConfigView getMachineConfig(AuthenticatedUser user, UUID machineId) {
    var machine = findScopedMachine(user, machineId);
    return resolveMachineConfig(machine);
  }

  @Transactional
  public MachineShiftConfigView setMachineConfig(AuthenticatedUser user, UUID machineId,
      List<ShiftWindowCommand> shifts) {
    requireMutationRole(user);
    jobScopes.requireLevelOrAbove(user, JOB_SCOPE_LEVEL);
    var machine = findScopedMachine(user, machineId);
    var normalized = validateShifts(shifts);
    var previous = storedMachineShifts(machineWindows.findAllByMachineIdOrderByShiftNumber(machine.getId()));
    if (normalized.isEmpty()) {
      clearOverride(user, machine, previous);
    } else if (previous.isEmpty()) {
      var saved = insertMachineWindows(machine, normalized);
      auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.MACHINE, machine.getId(),
          machine.getCode(), machine.getPlant().getId(), null, snapshot(storedMachineShifts(saved)), null));
      events.publishEvent(new ProjectionCacheEvictionEvent(machine.getId()));
    } else {
      machineWindows.deleteByMachineId(machine.getId());
      var saved = insertMachineWindows(machine, normalized);
      auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.MACHINE, machine.getId(),
          machine.getCode(), machine.getPlant().getId(), snapshot(previous), snapshot(storedMachineShifts(saved)), null));
      events.publishEvent(new ProjectionCacheEvictionEvent(machine.getId()));
    }
    return resolveMachineConfig(machine);
  }

  @Transactional
  public void clearMachineConfig(AuthenticatedUser user, UUID machineId) {
    requireMutationRole(user);
    jobScopes.requireLevelOrAbove(user, JOB_SCOPE_LEVEL);
    var machine = findScopedMachine(user, machineId);
    var previous = storedMachineShifts(machineWindows.findAllByMachineIdOrderByShiftNumber(machine.getId()));
    clearOverride(user, machine, previous);
  }

  /**
   * Server-side shift resolution without authentication or plant gating, for downstream
   * computation that enforces its own access control (story 8-6 projections). Reuses the exact
   * precedence of the user-facing read so MACHINE &gt; MACHINE_GROUP &gt; NONE has one owner.
   */
  @Transactional(readOnly = true)
  public MachineShiftConfigView resolveByMachineId(UUID machineId) {
    var machine = machines.findByIdWithPlantAndGroup(machineId).orElseThrow(MachineNotFoundException::new);
    return resolveMachineConfig(machine);
  }

  /** Overload for callers that already hold the machine, avoiding a duplicate fetch. */
  @Transactional(readOnly = true)
  public MachineShiftConfigView resolveByMachine(com.syncro.machine.infrastructure.MachineEntity machine) {
    return resolveMachineConfig(machine);
  }

  private void clearOverride(AuthenticatedUser user, MachineEntity machine, List<StoredShift> previous) {
    if (previous.isEmpty()) {
      return;
    }
    machineWindows.deleteByMachineId(machine.getId());
    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.MACHINE, machine.getId(),
        machine.getCode(), machine.getPlant().getId(), snapshot(previous), null, null));
    events.publishEvent(new ProjectionCacheEvictionEvent(machine.getId()));
  }

  private MachineShiftConfigView resolveMachineConfig(MachineEntity machine) {
    var ownWindows = storedMachineShifts(machineWindows.findAllByMachineIdOrderByShiftNumber(machine.getId()));
    if (!ownWindows.isEmpty()) {
      return new MachineShiftConfigView(SOURCE_MACHINE, false, toViews(ownWindows));
    }
    var inheritedWindows = storedGroupShifts(
        groupWindows.findAllByMachineGroupIdOrderByShiftNumber(machine.getMachineGroup().getId()));
    if (!inheritedWindows.isEmpty()) {
      return new MachineShiftConfigView(SOURCE_MACHINE_GROUP, true, toViews(inheritedWindows));
    }
    return new MachineShiftConfigView(SOURCE_NONE, false, List.of());
  }

  private List<ShiftWindowCommand> validateShifts(List<ShiftWindowCommand> shifts) {
    if (shifts == null || shifts.isEmpty()) {
      return List.of();
    }
    if (shifts.size() > MAX_SHIFTS) {
      throw new ValidationException(Map.of("shifts", "At most " + MAX_SHIFTS + " shifts are allowed."));
    }
    var problems = new ArrayList<String>();
    for (int i = 0; i < shifts.size(); i++) {
      var window = shifts.get(i);
      if (window == null || window.startTime() == null || window.endTime() == null) {
        problems.add("Shift " + (i + 1) + " requires both startTime and endTime.");
      } else if (window.startTime().equals(window.endTime())) {
        problems.add("Shift " + (i + 1) + " must not be zero-length.");
      }
    }
    if (!problems.isEmpty()) {
      throw new ValidationException(Map.of("shifts", String.join("; ", problems)));
    }
    return List.copyOf(shifts);
  }

  private List<MachineGroupShiftWindowEntity> insertGroupWindows(MachineGroupEntity group,
      List<ShiftWindowCommand> shifts) {
    var now = Instant.now(clock);
    var entities = new ArrayList<MachineGroupShiftWindowEntity>(shifts.size());
    for (int i = 0; i < shifts.size(); i++) {
      var window = shifts.get(i);
      entities.add(new MachineGroupShiftWindowEntity(UUID.randomUUID(), group, i + 1,
          window.startTime(), window.endTime(), now, now));
    }
    return groupWindows.saveAllAndFlush(entities);
  }

  private List<MachineShiftWindowEntity> insertMachineWindows(MachineEntity machine,
      List<ShiftWindowCommand> shifts) {
    var now = Instant.now(clock);
    var entities = new ArrayList<MachineShiftWindowEntity>(shifts.size());
    for (int i = 0; i < shifts.size(); i++) {
      var window = shifts.get(i);
      entities.add(new MachineShiftWindowEntity(UUID.randomUUID(), machine, i + 1,
          window.startTime(), window.endTime(), now, now));
    }
    return machineWindows.saveAllAndFlush(entities);
  }

  private MachineGroupEntity findScopedGroup(AuthenticatedUser user, UUID machineGroupId) {
    var group = machineGroups.findById(machineGroupId).orElseThrow(MachineGroupNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, group.getPlant().getId());
    }
    return group;
  }

  private MachineEntity findScopedMachine(AuthenticatedUser user, UUID machineId) {
    var machine = machines.findByIdWithPlantAndGroup(machineId).orElseThrow(MachineNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, machine.getPlant().getId());
    }
    return machine;
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN
        && user.applicationRole() != ApplicationRole.MANAGE) {
      throw new MutationForbiddenException();
    }
  }

  private MachineGroupShiftConfigView toGroupView(List<StoredShift> windows) {
    return new MachineGroupShiftConfigView(toViews(windows));
  }

  private List<ShiftWindowView> toViews(List<StoredShift> windows) {
    return windows.stream()
        .map(window -> new ShiftWindowView(window.shiftNumber(), window.startTime(), window.endTime()))
        .toList();
  }

  private List<StoredShift> storedGroupShifts(List<MachineGroupShiftWindowEntity> windows) {
    return windows.stream()
        .map(window -> new StoredShift(window.getShiftNumber(), window.getStartTime(), window.getEndTime()))
        .toList();
  }

  private List<StoredShift> storedMachineShifts(List<MachineShiftWindowEntity> windows) {
    return windows.stream()
        .map(window -> new StoredShift(window.getShiftNumber(), window.getStartTime(), window.getEndTime()))
        .toList();
  }

  private Map<String, Object> snapshot(List<StoredShift> windows) {
    if (windows.isEmpty()) {
      return null;
    }
    return Map.of("shifts", windows.stream()
        .map(window -> Map.<String, Object>of(
            "shiftNumber", window.shiftNumber(),
            "startTime", window.startTime().format(WIRE_TIME),
            "endTime", window.endTime().format(WIRE_TIME)))
        .toList());
  }

  public record ShiftWindowCommand(LocalTime startTime, LocalTime endTime) {
  }

  public record ShiftWindowView(int shiftNumber, LocalTime startTime, LocalTime endTime) {
  }

  public record MachineGroupShiftConfigView(List<ShiftWindowView> shifts) {
  }

  public record MachineShiftConfigView(String source, boolean inheritedFromGroup, List<ShiftWindowView> shifts) {
  }

  private record StoredShift(int shiftNumber, LocalTime startTime, LocalTime endTime) {
  }

  public static class ValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public ValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = fieldErrors == null ? Map.of() : new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }

  public static class MutationForbiddenException extends RuntimeException {
  }

  public static class MachineGroupNotFoundException extends RuntimeException {
  }

  public static class MachineNotFoundException extends RuntimeException {
  }
}