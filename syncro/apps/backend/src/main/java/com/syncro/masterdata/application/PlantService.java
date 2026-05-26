package com.syncro.masterdata.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlantService {
  private final PlantRepository plants;
  private final AuthUserPlantAssignmentRepository assignments;
  private final PlantScopeService plantScopes;
  private final Clock clock;

  public PlantService(
      PlantRepository plants,
      AuthUserPlantAssignmentRepository assignments,
      PlantScopeService plantScopes,
      Clock clock) {
    this.plants = plants;
    this.assignments = assignments;
    this.plantScopes = plantScopes;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<PlantView> list(AuthenticatedUser user) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return plants.findAll().stream()
          .sorted(Comparator.comparing(PlantEntity::getCode))
          .map(this::toView)
          .toList();
    }
    return plantScopes.effectiveScope(user).availablePlants().stream()
        .map(plant -> plants.findById(UUID.fromString(plant.id())).orElse(null))
        .filter(java.util.Objects::nonNull)
        .sorted(Comparator.comparing(PlantEntity::getCode))
        .map(this::toView)
        .toList();
  }

  @Transactional(readOnly = true)
  public PlantView get(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    }
    return plants.findById(plantId).map(this::toView).orElseThrow(PlantNotFoundException::new);
  }

  @Transactional
  public PlantView create(AuthenticatedUser user, CreatePlantCommand command) {
    requireMutationRole(user);
    var code = normalizeCode(command.code());
    var name = normalizeName(command.name());
    if (plants.existsByCodeIgnoreCase(code)) {
      throw new DuplicatePlantCodeException();
    }
    var now = Instant.now(clock);
    var plant = savePlant(new PlantEntity(UUID.randomUUID(), code, name, now, now));
    if (user.applicationRole() == ApplicationRole.MANAGE) {
      assignments.save(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plant.getId(), now));
    }
    return toView(plant);
  }

  @Transactional
  public PlantView update(AuthenticatedUser user, UUID plantId, CreatePlantCommand command) {
    requireMutationRole(user);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    }
    var plant = plants.findById(plantId).orElseThrow(PlantNotFoundException::new);
    var code = normalizeCode(command.code());
    var existing = plants.findByCodeIgnoreCase(code);
    if (existing.isPresent() && !existing.get().getId().equals(plantId)) {
      throw new DuplicatePlantCodeException();
    }
    plant.update(code, normalizeName(command.name()), Instant.now(clock));
    return toView(savePlant(plant));
  }

  @Transactional
  public void delete(AuthenticatedUser user, UUID plantId) {
    requireMutationRole(user);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    }
    if (!plants.existsById(plantId)) {
      throw new PlantNotFoundException();
    }
    plants.deleteById(plantId);
    plants.flush();
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN && user.applicationRole() != ApplicationRole.MANAGE) {
      throw new PlantMutationForbiddenException();
    }
  }

  private PlantEntity savePlant(PlantEntity plant) {
    try {
      return plants.saveAndFlush(plant);
    } catch (DataIntegrityViolationException exception) {
      throw new DuplicatePlantCodeException();
    }
  }

  private String normalizeCode(String code) {
    return code.trim().toUpperCase();
  }

  private String normalizeName(String name) {
    return name.trim();
  }

  private PlantView toView(PlantEntity plant) {
    return new PlantView(plant.getId(), plant.getCode(), plant.getName(), plant.getCreatedAt(), plant.getUpdatedAt());
  }

  public record CreatePlantCommand(String code, String name) {
  }

  public record PlantView(UUID id, String code, String name, Instant createdAt, Instant updatedAt) {
  }

  public static class DuplicatePlantCodeException extends RuntimeException {
  }

  public static class PlantMutationForbiddenException extends RuntimeException {
  }

  public static class PlantNotFoundException extends RuntimeException {
  }
}
