package com.syncro.machine.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MachineService {
  private static final String DUPLICATE_MACHINE_CODE_CONSTRAINT = "uq_machines_plant_id_lower_code";
  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int MAX_PAGE_SIZE = 200;
  private static final Set<String> ALLOWED_SORTS = Set.of("code", "name", "status", "createdAt", "updatedAt");
  private static final Pattern MACHINE_CODE_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,63}$");
  private static final Set<String> RESERVED_OPTIONAL_FIELDS = Set.of(
      "running", "runtimeHours", "counting", "countingDelta", "plantCode", "machineCode", "traceId", "receivedAt");
  private static final Pattern OPTIONAL_FIELD_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");
  private static final int MAX_OPTIONAL_FIELDS = 10;
  private static final int MAX_OPTIONAL_FIELD_LENGTH = 64;

  private final MachineRepository machines;
  private final PlantRepository plants;
  private final MachineGroupRepository machineGroups;
  private final PlantScopeService plantScopes;
  private final AuthUserPlantAssignmentRepository assignments;
  private final AuditLogWriter auditLog;
  private final OperationalScopeService operationalScopes;
  private final Clock clock;

  public MachineService(MachineRepository machines, PlantRepository plants, MachineGroupRepository machineGroups,
      PlantScopeService plantScopes, AuthUserPlantAssignmentRepository assignments, AuditLogWriter auditLog,
      OperationalScopeService operationalScopes, Clock clock) {
    this.machines = machines;
    this.plants = plants;
    this.machineGroups = machineGroups;
    this.plantScopes = plantScopes;
    this.assignments = assignments;
    this.auditLog = auditLog;
    this.operationalScopes = operationalScopes;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public MachineListView list(AuthenticatedUser user, UUID plantId, UUID machineGroupId, MachineStatus status) {
    return list(user, plantId, machineGroupId, status, null, 0, DEFAULT_PAGE_SIZE, "code,asc");
  }

  @Transactional(readOnly = true)
  public MachineListView list(AuthenticatedUser user, UUID plantId, UUID machineGroupId, MachineStatus status,
      String search, Integer limit) {
    return list(user, plantId, machineGroupId, status, search, 0, limit == null ? DEFAULT_PAGE_SIZE : limit, "code,asc");
  }

  @Transactional(readOnly = true)
  public MachineListView list(AuthenticatedUser user, UUID plantId, UUID machineGroupId, MachineStatus status,
      String search, int page, int size, String sort) {
    var superAdmin = user.applicationRole() == ApplicationRole.SUPER_ADMIN;
    List<UUID> scopedPlantIds = List.of();
    if (!superAdmin) {
      scopedPlantIds = assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
          .map(assignment -> assignment.getPlantId())
          .toList();
      if (plantId != null) {
        plantScopes.requirePlantAccess(user, plantId);
      }
    } else if (plantId != null && !plants.existsById(plantId)) {
      throw new PlantNotFoundForMachineException();
    }
    if (machineGroupId != null) {
      var group = machineGroups.findById(machineGroupId).orElseThrow(MachineGroupNotFoundForMachineException::new);
      if (!superAdmin) {
        plantScopes.requirePlantAccess(user, group.getPlant().getId());
      }
      if (plantId != null && !group.getPlant().getId().equals(plantId)) {
        throw new MachineGroupPlantMismatchException();
      }
    }
    var normalizedPage = normalizePage(page);
    var normalizedSize = normalizeSize(size);
    var normalizedSort = normalizeSort(sort);
    if (!superAdmin && scopedPlantIds.isEmpty()) {
      return new MachineListView(List.of(), 0, normalizedPage, normalizedSize, sortName(normalizedSort));
    }
    var normalizedSearch = normalizeSearch(search);
    var pageable = PageRequest.of(normalizedPage, normalizedSize, normalizedSort);
    var result = superAdmin
        ? machines.findAllUnscoped(plantId, machineGroupId, status, normalizedSearch, pageable)
        : machines.findAllScoped(scopedPlantIds, plantId, machineGroupId, scopedMachineGroupIds(user), status, normalizedSearch, pageable);
    return new MachineListView(
        result.stream().map(this::toView).toList(), result.getTotalElements(), normalizedPage, normalizedSize, sortName(normalizedSort));
  }

  @Transactional(readOnly = true)
  public MachineView get(AuthenticatedUser user, UUID machineId) {
    return toView(findScoped(user, machineId));
  }

  @Transactional(readOnly = true)
  public MachineView getByCode(AuthenticatedUser user, String machineCode) {
    var normalized = normalizeCode(machineCode);
    var machine = machines.findByCodeIgnoreCase(normalized)
        .orElseThrow(MachineNotFoundException::new);
    
    // Check plant assignment for non-SUPER_ADMIN users
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, machine.getPlant().getId());
    }
    
    return toView(machine);
  }

  @Transactional
  public MachineView create(AuthenticatedUser user, MachineCommand command) {
    requireMutationRole(user);
    validateRequiredCommandFields(command);
    List<String> optionalTelemetryFields = command.optionalTelemetryFields() == null
        ? List.of()
        : normalizeOptionalTelemetryFields(command.optionalTelemetryFields());
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, command.plantId());
    } else if (!plants.existsById(command.plantId())) {
      throw new PlantNotFoundForMachineException();
    }
    var plant = plants.findById(command.plantId()).orElseThrow(PlantNotFoundForMachineException::new);
    var machineGroup = machineGroups.findById(command.machineGroupId()).orElseThrow(MachineGroupNotFoundForMachineException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, machineGroup.getPlant().getId());
    }
    if (!machineGroup.getPlant().getId().equals(command.plantId())) {
      throw new MachineGroupPlantMismatchException();
    }
    var code = normalizeCode(command.code());
    if (machines.existsByPlantIdAndCodeIgnoreCase(command.plantId(), code)) {
      throw new DuplicateMachineCodeException();
    }
    var now = Instant.now(clock);
    var machine = saveMachine(new MachineEntity(UUID.randomUUID(), plant, machineGroup, code, normalizeOptional(command.name()),
        command.status(), normalizeOptional(command.brand()), command.installedAt(), normalizeOptional(command.notes()),
        optionalTelemetryFields, now, now));
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.MACHINE, machine.getId(), machine.getCode(),
        command.plantId(), null, MachineAuditValues.of(machine)));
    return toView(machine);
  }

  @Transactional
  public MachineView update(AuthenticatedUser user, UUID machineId, MachineCommand command) {
    requireMutationRole(user);
    var machine = findScoped(user, machineId);
    validateRequiredCommandFields(command);
    // DW-32: absent optionalTelemetryFields (JSON key omitted -> null) leaves the stored
    // configuration untouched; an explicit empty array clears it; a populated list replaces it.
    var optionalTelemetryFields = command.optionalTelemetryFields() == null
        ? machine.getOptionalTelemetryFields()
        : normalizeOptionalTelemetryFields(command.optionalTelemetryFields());
    var plantId = machine.getPlant().getId();
    if (!plantId.equals(command.plantId())) {
      throw new MachineDataIntegrityException();
    }
    var machineGroup = machineGroups.findById(command.machineGroupId()).orElseThrow(MachineGroupNotFoundForMachineException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, machineGroup.getPlant().getId());
    }
    if (!machineGroup.getPlant().getId().equals(plantId)) {
      throw new MachineGroupPlantMismatchException();
    }
    var entityLabel = machine.getCode();
    var previous = MachineAuditValues.of(machine);
    var code = normalizeCode(command.code());
    var existing = machines.findByPlantIdAndCodeIgnoreCase(plantId, code);
    if (existing.isPresent() && !existing.get().getId().equals(machineId)) {
      throw new DuplicateMachineCodeException();
    }
    machine.update(machineGroup, code, normalizeOptional(command.name()), command.status(), normalizeOptional(command.brand()),
        command.installedAt(), normalizeOptional(command.notes()), optionalTelemetryFields, Instant.now(clock));
    var saved = saveMachine(machine);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.MACHINE, machineId, entityLabel, plantId,
        previous, MachineAuditValues.of(saved)));
    return toView(saved);
  }

  @Transactional
  public void delete(AuthenticatedUser user, UUID machineId) {
    requireMutationRole(user);
    var machine = findScoped(user, machineId);
    var entityLabel = machine.getCode();
    var previous = MachineAuditValues.of(machine);
    try {
      machines.delete(machine);
      machines.flush();
      auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.MACHINE, machineId, entityLabel,
          machine.getPlant().getId(), previous, null));
    } catch (DataIntegrityViolationException exception) {
      throw new MachineDataIntegrityException();
    }
  }

  private MachineEntity findScoped(AuthenticatedUser user, UUID machineId) {
    var machine = machines.findByIdWithPlantAndGroup(machineId).orElseThrow(MachineNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, machine.getPlant().getId());
    }
    return machine;
  }

  /**
   * Derived section-leader machineGroupIds (AD-2). Empty derived set = no group
   * restriction — Phase 1 plant-scope behavior preserved for non-leaders; a leader
   * sees only their own groups (sibling-group data in the same section excluded).
   */
  private List<UUID> scopedMachineGroupIds(AuthenticatedUser user) {
    var machineGroupIds = operationalScopes.derive(user).machineGroupIds();
    return machineGroupIds.isEmpty() ? null : List.copyOf(machineGroupIds);
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN && user.applicationRole() != ApplicationRole.MANAGE) {
      throw new MachineMutationForbiddenException();
    }
  }

  private MachineEntity saveMachine(MachineEntity machine) {
    try {
      return machines.saveAndFlush(machine);
    } catch (DataIntegrityViolationException exception) {
      if (isDuplicateMachineCodeViolation(exception)) {
        throw new DuplicateMachineCodeException();
      }
      throw new MachineDataIntegrityException();
    }
  }

  private boolean isDuplicateMachineCodeViolation(DataIntegrityViolationException exception) {
    var cause = exception.getCause();
    while (cause != null) {
      if (cause instanceof ConstraintViolationException constraint
          && DUPLICATE_MACHINE_CODE_CONSTRAINT.equalsIgnoreCase(constraint.getConstraintName())) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private void validateRequiredCommandFields(MachineCommand command) {
    Map<String, String> missing = new LinkedHashMap<>();
    if (command.plantId() == null) {
      missing.put("plantId", "Plant is required.");
    }
    if (command.machineGroupId() == null) {
      missing.put("machineGroupId", "Machine group is required.");
    }
    if (command.status() == null) {
      missing.put("status", "Status is required.");
    }
    if (!missing.isEmpty()) {
      throw new MachineValidationException(missing);
    }
  }

  private List<String> normalizeOptionalTelemetryFields(List<String> configured) {
    if (configured == null || configured.isEmpty()) {
      return List.of();
    }
    var normalized = new ArrayList<String>();
    // DW-118: case-insensitive guards — reserved names and duplicates are compared
    // case-insensitively (Locale.ROOT) while the user's casing is preserved in storage.
    var seenLower = new java.util.HashSet<String>();
    for (String entry : configured) {
      var trimmed = entry == null ? "" : entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      String reason = optionalFieldRejectionReason(trimmed);
      if (reason != null) {
        throw new MachineValidationException(Map.of("optionalTelemetryFields", "'" + trimmed + "' " + reason));
      }
      if (seenLower.add(trimmed.toLowerCase(Locale.ROOT))) {
        normalized.add(trimmed);
      }
    }
    if (normalized.size() > MAX_OPTIONAL_FIELDS) {
      throw new MachineValidationException(Map.of("optionalTelemetryFields",
          "At most " + MAX_OPTIONAL_FIELDS + " optional telemetry fields are allowed."));
    }
    return List.copyOf(normalized);
  }

  private static String optionalFieldRejectionReason(String trimmed) {
    if (trimmed.length() > MAX_OPTIONAL_FIELD_LENGTH) {
      return "must be at most " + MAX_OPTIONAL_FIELD_LENGTH + " characters.";
    }
    if (trimmed.startsWith("_")) {
      return "must not start with an underscore.";
    }
    if (!OPTIONAL_FIELD_PATTERN.matcher(trimmed).matches()) {
      return "may only contain letters, numbers and underscores.";
    }
    var lower = trimmed.toLowerCase(Locale.ROOT);
    if (RESERVED_OPTIONAL_FIELDS.stream().anyMatch(reserved -> reserved.toLowerCase(Locale.ROOT).equals(lower))) {
      return "is reserved by the base telemetry contract.";
    }
    return null;
  }

  private String normalizeSearch(String search) {
    if (search == null || search.isBlank()) {
      return null;
    }
    var normalized = search.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + normalized + "%";
  }

  private int normalizePage(int page) {
    if (page < 0) {
      throw new MachineValidationException(Map.of("page", "Page must be zero or greater."));
    }
    return page;
  }

  private int normalizeSize(int size) {
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new MachineValidationException(Map.of("size", "Size must be between 1 and " + MAX_PAGE_SIZE + "."));
    }
    return size;
  }

  private Sort normalizeSort(String sort) {
    if (sort == null || sort.isBlank()) {
      return Sort.by(Sort.Direction.ASC, "code");
    }
    var parts = sort.split(",", 2);
    var property = parts[0].trim();
    if (!ALLOWED_SORTS.contains(property)) {
      throw new MachineValidationException(Map.of("sort", "Sort property is not allowed."));
    }
    var direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.DESC : Sort.Direction.ASC;
    return Sort.by(direction, property);
  }

  private String sortName(Sort sort) {
    var order = sort.iterator().next();
    return order.getProperty() + "," + order.getDirection().name().toLowerCase(Locale.ROOT);
  }

  private String normalizeCode(String code) {
    if (code == null) {
      throw new MachineValidationException(Map.of("code", "Machine code is required."));
    }
    var normalized = code.trim().toUpperCase(Locale.ROOT);
    if (!MACHINE_CODE_PATTERN.matcher(normalized).matches()) {
      throw new MachineValidationException(Map.of("code", "Machine code format is invalid."));
    }
    return normalized;
  }

  private String normalizeOptional(String value) {
    if (value == null) {
      return null;
    }
    var trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private MachineView toView(MachineEntity machine) {
    var plant = machine.getPlant();
    var machineGroup = machine.getMachineGroup();
    return new MachineView(machine.getId(), plant.getId(), plant.getCode(), plant.getName(), machineGroup.getId(),
        machineGroup.getName(), machine.getCode(), machine.getName(), machine.getStatus(), machine.getBrand(),
        machine.getInstalledAt(), machine.getNotes(), machine.getCreatedAt(), machine.getUpdatedAt(),
        machine.getOptionalTelemetryFields() == null ? List.of() : machine.getOptionalTelemetryFields());
  }

  public record MachineCommand(UUID plantId, UUID machineGroupId, String code, String name, MachineStatus status,
      String brand, LocalDate installedAt, String notes, List<String> optionalTelemetryFields) {
  }

  public record MachineView(UUID id, UUID plantId, String plantCode, String plantName, UUID machineGroupId,
      String machineGroupName, String code, String name, MachineStatus status, String brand, LocalDate installedAt,
      String notes, Instant createdAt, Instant updatedAt, List<String> optionalTelemetryFields) {
  }

  public record MachineListView(List<MachineView> items, long totalElements, int page, int size, String sort) {
  }

  public static class DuplicateMachineCodeException extends RuntimeException {
  }

  public static class MachineDataIntegrityException extends RuntimeException {
  }

  public static class MachineGroupNotFoundForMachineException extends RuntimeException {
  }

  public static class MachineGroupPlantMismatchException extends RuntimeException {
  }

  public static class MachineMutationForbiddenException extends RuntimeException {
  }

  public static class MachineNotFoundException extends RuntimeException {
  }

  public static class MachineValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public MachineValidationException() {
      this.fieldErrors = Map.of();
    }

    public MachineValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = fieldErrors == null
          ? Map.of()
          : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }

  public static class PlantNotFoundForMachineException extends RuntimeException {
  }
}
