package com.syncro.masterdata.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
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
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public PlantService(
      PlantRepository plants,
      AuthUserPlantAssignmentRepository assignments,
      PlantScopeService plantScopes,
      AuditLogWriter auditLog,
      Clock clock) {
    this.plants = plants;
    this.assignments = assignments;
    this.plantScopes = plantScopes;
    this.auditLog = auditLog;
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
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.PLANT, plant.getId(), plant.getCode(),
        plant.getId(), null, PlantAuditValues.of(plant)));
    return toView(plant);
  }

  @Transactional
  public PlantView update(AuthenticatedUser user, UUID plantId, CreatePlantCommand command) {
    requireMutationRole(user);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    }
    var plant = plants.findById(plantId).orElseThrow(PlantNotFoundException::new);
    var entityLabel = plant.getCode();
    var previous = PlantAuditValues.of(plant);
    var code = normalizeCode(command.code());
    var existing = plants.findByCodeIgnoreCase(code);
    if (existing.isPresent() && !existing.get().getId().equals(plantId)) {
      throw new DuplicatePlantCodeException();
    }
    plant.update(code, normalizeName(command.name()), Instant.now(clock));
    var saved = savePlant(plant);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PLANT, plantId, entityLabel, plantId,
        previous, PlantAuditValues.of(saved)));
    return toView(saved);
  }

  @Transactional
  public void delete(AuthenticatedUser user, UUID plantId) {
    requireMutationRole(user);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    }
    var plant = plants.findById(plantId).orElseThrow(PlantNotFoundException::new);
    var entityLabel = plant.getCode();
    var previous = PlantAuditValues.of(plant);
    plants.delete(plant);
    plants.flush();
    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.PLANT, plantId, entityLabel, null,
        previous, null));
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
      if (isPlantCodeUniqueViolation(exception)) {
        throw new DuplicatePlantCodeException();
      }
      throw new PlantDataIntegrityException();
    }
  }

  private boolean isPlantCodeUniqueViolation(DataIntegrityViolationException exception) {
    var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
    return message.contains("uq_plants_code") || message.contains("plants_code_key");
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

  public static class PlantDataIntegrityException extends RuntimeException {
  }

  public static class PlantMutationForbiddenException extends RuntimeException {
  }

  public static class PlantNotFoundException extends RuntimeException {
  }
}
