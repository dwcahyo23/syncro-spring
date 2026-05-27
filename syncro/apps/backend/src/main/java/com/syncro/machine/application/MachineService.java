package com.syncro.machine.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MachineService {
  private static final String DUPLICATE_MACHINE_CODE_CONSTRAINT = "uq_machines_plant_id_lower_code";
  private static final Pattern MACHINE_CODE_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,63}$");

  private final MachineRepository machines;
  private final PlantRepository plants;
  private final MachineGroupRepository machineGroups;
  private final PlantScopeService plantScopes;
  private final AuthUserPlantAssignmentRepository assignments;
  private final Clock clock;

  public MachineService(MachineRepository machines, PlantRepository plants, MachineGroupRepository machineGroups,
      PlantScopeService plantScopes, AuthUserPlantAssignmentRepository assignments, Clock clock) {
    this.machines = machines;
    this.plants = plants;
    this.machineGroups = machineGroups;
    this.plantScopes = plantScopes;
    this.assignments = assignments;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<MachineView> list(AuthenticatedUser user, UUID plantId, UUID machineGroupId, MachineStatus status) {
    var superAdmin = user.applicationRole() == ApplicationRole.SUPER_ADMIN;
    List<UUID> scopedPlantIds = List.of();
    if (!superAdmin) {
      scopedPlantIds = assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
          .map(assignment -> assignment.getPlantId())
          .toList();
      if (plantId != null) {
        plantScopes.requirePlantAccess(user, plantId);
      }
      if (scopedPlantIds.isEmpty()) {
        return List.of();
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
    var result = superAdmin
        ? machines.findAllUnscoped(plantId, machineGroupId, status)
        : machines.findAllScoped(scopedPlantIds, plantId, machineGroupId, status);
    return result.stream().map(this::toView).toList();
  }

  @Transactional(readOnly = true)
  public MachineView get(AuthenticatedUser user, UUID machineId) {
    return toView(findScoped(user, machineId));
  }

  @Transactional
  public MachineView create(AuthenticatedUser user, MachineCommand command) {
    requireMutationRole(user);
    validateCommand(command);
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
    return toView(saveMachine(new MachineEntity(UUID.randomUUID(), plant, machineGroup, code, normalizeOptional(command.name()),
        command.status(), normalizeOptional(command.brand()), command.installedAt(), normalizeOptional(command.notes()), now, now)));
  }

  @Transactional
  public MachineView update(AuthenticatedUser user, UUID machineId, MachineCommand command) {
    requireMutationRole(user);
    validateCommand(command);
    var machine = findScoped(user, machineId);
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
    var code = normalizeCode(command.code());
    var existing = machines.findByPlantIdAndCodeIgnoreCase(plantId, code);
    if (existing.isPresent() && !existing.get().getId().equals(machineId)) {
      throw new DuplicateMachineCodeException();
    }
    machine.update(machineGroup, code, normalizeOptional(command.name()), command.status(), normalizeOptional(command.brand()),
        command.installedAt(), normalizeOptional(command.notes()), Instant.now(clock));
    return toView(saveMachine(machine));
  }

  @Transactional
  public void delete(AuthenticatedUser user, UUID machineId) {
    requireMutationRole(user);
    var machine = findScoped(user, machineId);
    try {
      machines.delete(machine);
      machines.flush();
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

  private void validateCommand(MachineCommand command) {
    if (command.plantId() == null || command.machineGroupId() == null || command.status() == null) {
      throw new MachineValidationException();
    }
  }

  private String normalizeCode(String code) {
    if (code == null) {
      throw new MachineValidationException();
    }
    var normalized = code.trim().toUpperCase(Locale.ROOT);
    if (!MACHINE_CODE_PATTERN.matcher(normalized).matches()) {
      throw new MachineValidationException();
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
        machine.getInstalledAt(), machine.getNotes(), machine.getCreatedAt(), machine.getUpdatedAt());
  }

  public record MachineCommand(UUID plantId, UUID machineGroupId, String code, String name, MachineStatus status,
      String brand, LocalDate installedAt, String notes) {
  }

  public record MachineView(UUID id, UUID plantId, String plantCode, String plantName, UUID machineGroupId,
      String machineGroupName, String code, String name, MachineStatus status, String brand, LocalDate installedAt,
      String notes, Instant createdAt, Instant updatedAt) {
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
  }

  public static class PlantNotFoundForMachineException extends RuntimeException {
  }
}
