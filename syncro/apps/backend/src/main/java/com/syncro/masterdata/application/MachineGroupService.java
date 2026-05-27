package com.syncro.masterdata.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MachineGroupService {
  private final MachineGroupRepository machineGroups;
  private final PlantRepository plants;
  private final PlantScopeService plantScopes;
  private final Clock clock;

  public MachineGroupService(
      MachineGroupRepository machineGroups,
      PlantRepository plants,
      PlantScopeService plantScopes,
      Clock clock) {
    this.machineGroups = machineGroups;
    this.plants = plants;
    this.plantScopes = plantScopes;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<MachineGroupView> list(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    } else if (!plants.existsById(plantId)) {
      throw new PlantNotFoundForMachineGroupException();
    }
    return machineGroups.findByPlantIdOrderByNameAsc(plantId).stream()
        .map(this::toView)
        .toList();
  }

  @Transactional(readOnly = true)
  public MachineGroupView get(AuthenticatedUser user, UUID machineGroupId) {
    var machineGroup = findScoped(user, machineGroupId);
    return toView(machineGroup);
  }

  @Transactional
  public MachineGroupView create(AuthenticatedUser user, CreateMachineGroupCommand command) {
    requireMutationRole(user);
    var plantId = command.plantId();
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    }
    var plant = plants.findById(plantId).orElseThrow(PlantNotFoundForMachineGroupException::new);
    var name = normalizeName(command.name());
    if (machineGroups.existsByPlantIdAndNameIgnoreCase(plantId, name)) {
      throw new DuplicateMachineGroupNameException();
    }
    var now = Instant.now(clock);
    return toView(saveMachineGroup(new MachineGroupEntity(UUID.randomUUID(), plant, name, now, now)));
  }

  @Transactional
  public MachineGroupView update(AuthenticatedUser user, UUID machineGroupId, CreateMachineGroupCommand command) {
    requireMutationRole(user);
    var machineGroup = findScoped(user, machineGroupId);
    var plantId = command.plantId();
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    }
    var plant = plants.findById(plantId).orElseThrow(PlantNotFoundForMachineGroupException::new);
    var name = normalizeName(command.name());
    var existing = machineGroups.findByPlantIdAndNameIgnoreCase(plantId, name);
    if (existing.isPresent() && !existing.get().getId().equals(machineGroupId)) {
      throw new DuplicateMachineGroupNameException();
    }
    machineGroup.update(plant, name, Instant.now(clock));
    return toView(saveMachineGroup(machineGroup));
  }

  @Transactional
  public void delete(AuthenticatedUser user, UUID machineGroupId) {
    requireMutationRole(user);
    var machineGroup = findScoped(user, machineGroupId);
    machineGroups.delete(machineGroup);
    machineGroups.flush();
  }

  private MachineGroupEntity findScoped(AuthenticatedUser user, UUID machineGroupId) {
    var machineGroup = machineGroups.findById(machineGroupId).orElseThrow(MachineGroupNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, machineGroup.getPlant().getId());
    }
    return machineGroup;
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN && user.applicationRole() != ApplicationRole.MANAGE) {
      throw new MachineGroupMutationForbiddenException();
    }
  }

  private MachineGroupEntity saveMachineGroup(MachineGroupEntity machineGroup) {
    try {
      return machineGroups.saveAndFlush(machineGroup);
    } catch (DataIntegrityViolationException exception) {
      if (isMachineGroupNameUniqueViolation(exception)) {
        throw new DuplicateMachineGroupNameException();
      }
      throw new MachineGroupDataIntegrityException();
    }
  }

  private boolean isMachineGroupNameUniqueViolation(DataIntegrityViolationException exception) {
    var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
    return message.contains("uq_machine_groups_plant_id_name");
  }

  private String normalizeName(String name) {
    return name.trim();
  }

  private MachineGroupView toView(MachineGroupEntity machineGroup) {
    var plant = machineGroup.getPlant();
    return new MachineGroupView(
        machineGroup.getId(),
        plant.getId(),
        plant.getCode(),
        plant.getName(),
        machineGroup.getName(),
        machineGroup.getCreatedAt(),
        machineGroup.getUpdatedAt());
  }

  public record CreateMachineGroupCommand(UUID plantId, String name) {
  }

  public record MachineGroupView(
      UUID id,
      UUID plantId,
      String plantCode,
      String plantName,
      String name,
      Instant createdAt,
      Instant updatedAt) {
  }

  public static class DuplicateMachineGroupNameException extends RuntimeException {
  }

  public static class MachineGroupDataIntegrityException extends RuntimeException {
  }

  public static class MachineGroupMutationForbiddenException extends RuntimeException {
  }

  public static class MachineGroupNotFoundException extends RuntimeException {
  }

  public static class PlantNotFoundForMachineGroupException extends RuntimeException {
  }
}
