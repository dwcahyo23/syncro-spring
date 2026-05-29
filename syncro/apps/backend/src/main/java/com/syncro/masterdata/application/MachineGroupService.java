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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MachineGroupService {
  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int MAX_PAGE_SIZE = 200;
  private static final Set<String> ALLOWED_SORTS = Set.of("name", "createdAt", "updatedAt");

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
  public MachineGroupListView list(AuthenticatedUser user, UUID plantId) {
    return list(user, plantId, null, 0, DEFAULT_PAGE_SIZE, "name,asc");
  }

  @Transactional(readOnly = true)
  public MachineGroupListView list(AuthenticatedUser user, UUID plantId, String search, int page, int size, String sort) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    } else if (!plants.existsById(plantId)) {
      throw new PlantNotFoundForMachineGroupException();
    }
    var normalizedPage = normalizePage(page);
    var normalizedSize = normalizeSize(size);
    var normalizedSort = normalizeSort(sort);
    var result = machineGroups.search(plantId, normalizeSearch(search), PageRequest.of(normalizedPage, normalizedSize, normalizedSort));
    return new MachineGroupListView(
        result.stream().map(this::toView).toList(), result.getTotalElements(), normalizedPage, normalizedSize, sortName(normalizedSort));
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
    var plant = machineGroup.getPlant();
    var plantId = plant.getId();
    if (!plantId.equals(command.plantId())) {
      throw new MachineGroupDataIntegrityException();
    }
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
    try {
      machineGroups.delete(machineGroup);
      machineGroups.flush();
    } catch (DataIntegrityViolationException exception) {
      throw new MachineGroupDataIntegrityException();
    }
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
    return message.contains("uq_machine_groups_plant_id_name")
        || message.contains("uq_machine_groups_plant_id_lower_name");
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
      throw new MachineGroupDataIntegrityException();
    }
    return page;
  }

  private int normalizeSize(int size) {
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new MachineGroupDataIntegrityException();
    }
    return size;
  }

  private Sort normalizeSort(String sort) {
    if (sort == null || sort.isBlank()) {
      return Sort.by(Sort.Direction.ASC, "name");
    }
    var parts = sort.split(",", 2);
    var property = parts[0].trim();
    if (!ALLOWED_SORTS.contains(property)) {
      throw new MachineGroupDataIntegrityException();
    }
    var direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.DESC : Sort.Direction.ASC;
    return Sort.by(direction, property);
  }

  private String sortName(Sort sort) {
    var order = sort.iterator().next();
    return order.getProperty() + "," + order.getDirection().name().toLowerCase(Locale.ROOT);
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

  public record MachineGroupListView(List<MachineGroupView> items, long totalElements, int page, int size, String sort) {
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
