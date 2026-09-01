package com.syncro.org.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.org.infrastructure.db.MachineAreaEntity;
import com.syncro.org.infrastructure.db.MachineAreaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plant-scoped machine areas (physical locations, blueprint A11). CRUD mirrors
 * {@code DepartmentService}: soft-inactivate only, never hard-delete; deactivation is
 * rejected while machines still reference the area. Mutations require
 * SUPER_ADMIN/MANAGER_MAINTENANCE (OPA enforces the same gate), and non-SUPER_ADMIN
 * mutations require plant access. Every mutation is audit-logged with
 * {@code MACHINE_AREA}.
 */
@Service
public class MachineAreaService {

  private static final String AREA_NAME_UNIQUE_CONSTRAINT = "uq_machine_areas_plant_name";
  private static final String AREA_CODE_UNIQUE_CONSTRAINT = "uq_machine_areas_plant_code";

  private final MachineAreaRepository areas;
  private final MachineRepository machines;
  private final PlantRepository plants;
  private final PlantScopeService plantScopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public MachineAreaService(
      MachineAreaRepository areas,
      MachineRepository machines,
      PlantRepository plants,
      PlantScopeService plantScopes,
      AuditLogWriter auditLog,
      Clock clock) {
    this.areas = areas;
    this.machines = machines;
    this.plants = plants;
    this.plantScopes = plantScopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public MachineAreaListView list(AuthenticatedUser user, UUID plantId, boolean includeInactive) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    } else if (!plants.existsById(plantId)) {
      throw new PlantNotFoundForMachineAreaException();
    }
    var activeOnly = !includeInactive;
    var result = areas.findAllByPlantId(plantId, activeOnly);
    return new MachineAreaListView(result.stream().map(this::toView).toList());
  }

  @Transactional(readOnly = true)
  public MachineAreaView get(AuthenticatedUser user, UUID areaId) {
    return toView(findScoped(user, areaId));
  }

  @Transactional
  public MachineAreaView create(AuthenticatedUser user, CreateMachineAreaCommand command) {
    requireMutationRole(user);
    var plantId = command.plantId();
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    }
    var plant = plants.findById(plantId).orElseThrow(PlantNotFoundForMachineAreaException::new);
    var name = normalizeName(command.name());
    var code = normalizeOptional(command.code());
    validateUniqueness(plantId, name, code, null);
    var now = Instant.now(clock);
    var saved = saveArea(new MachineAreaEntity(UUID.randomUUID(), plantId, code, name,
        normalizeOptional(command.description()), true, now, now));
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.MACHINE_AREA, saved.getId(),
        saved.getName(), plantId, null, MachineAreaAuditValues.of(saved), null));
    return toView(saved);
  }

  @Transactional
  public MachineAreaView update(AuthenticatedUser user, UUID areaId, UpdateMachineAreaCommand command) {
    requireMutationRole(user);
    var area = findScoped(user, areaId);
    var plantId = area.getPlantId();
    var entityLabel = area.getName();
    var previous = MachineAreaAuditValues.of(area);
    var name = normalizeName(command.name());
    var code = normalizeOptional(command.code());
    validateUniqueness(plantId, name, code, areaId);
    area.update(code, name, normalizeOptional(command.description()), command.active(), Instant.now(clock));
    var saved = saveArea(area);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.MACHINE_AREA, areaId, entityLabel,
        plantId, previous, MachineAreaAuditValues.of(saved), null));
    return toView(saved);
  }

  /**
   * Deactivate-style delete: reject when machines still reference the area
   * (409 {@code MACHINE_AREA_HAS_MACHINES}); otherwise soft-inactivate. No hard delete.
   */
  @Transactional
  public void delete(AuthenticatedUser user, UUID areaId) {
    requireMutationRole(user);
    var area = findScoped(user, areaId);
    if (machines.countByAreaId(areaId) > 0) {
      throw new MachineAreaHasMachinesException();
    }
    var entityLabel = area.getName();
    var previous = MachineAreaAuditValues.of(area);
    area.deactivate(Instant.now(clock));
    var saved = saveArea(area);
    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.MACHINE_AREA, areaId, entityLabel,
        area.getPlantId(), previous, MachineAreaAuditValues.of(saved), null));
  }

  private MachineAreaEntity findScoped(AuthenticatedUser user, UUID areaId) {
    var area = areas.findById(areaId).orElseThrow(MachineAreaNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, area.getPlantId());
    }
    return area;
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN
        && user.applicationRole() != ApplicationRole.MANAGER_MAINTENANCE) {
      throw new MachineAreaMutationForbiddenException();
    }
  }

  private void validateUniqueness(UUID plantId, String name, String code, UUID excludeId) {
    if (areas.findByPlantIdAndNameIgnoreCase(plantId, name)
        .filter(existing -> !existing.getId().equals(excludeId))
        .isPresent()) {
      throw new DuplicateMachineAreaNameException();
    }
    if (code != null) {
      var codeOwner = areas.findByPlantIdAndCodeIgnoreCase(plantId, code);
      if (codeOwner.isPresent() && !codeOwner.get().getId().equals(excludeId)) {
        throw new DuplicateMachineAreaCodeException();
      }
    }
  }

  private MachineAreaEntity saveArea(MachineAreaEntity area) {
    try {
      return areas.saveAndFlush(area);
    } catch (DataIntegrityViolationException exception) {
      var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
      if (message.contains(AREA_NAME_UNIQUE_CONSTRAINT)) {
        throw new DuplicateMachineAreaNameException();
      }
      if (message.contains(AREA_CODE_UNIQUE_CONSTRAINT)) {
        throw new DuplicateMachineAreaCodeException();
      }
      throw new MachineAreaDataIntegrityException();
    }
  }

  private String normalizeName(String name) {
    return name == null ? null : name.trim();
  }

  private String normalizeOptional(String value) {
    if (value == null) {
      return null;
    }
    var trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private MachineAreaView toView(MachineAreaEntity area) {
    var plant = plants.findById(area.getPlantId()).orElse(null);
    return new MachineAreaView(
        area.getId(),
        area.getPlantId(),
        plant == null ? null : plant.getCode(),
        plant == null ? null : plant.getName(),
        area.getCode(),
        area.getName(),
        area.getDescription(),
        area.isActive(),
        area.getCreatedAt(),
        area.getUpdatedAt());
  }

  public record CreateMachineAreaCommand(UUID plantId, String code, String name, String description) {
  }

  public record UpdateMachineAreaCommand(String code, String name, String description, Boolean active) {
  }

  public record MachineAreaView(
      UUID id,
      UUID plantId,
      String plantCode,
      String plantName,
      String code,
      String name,
      String description,
      boolean active,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record MachineAreaListView(List<MachineAreaView> items) {
  }

  public static class DuplicateMachineAreaNameException extends RuntimeException {
  }

  public static class DuplicateMachineAreaCodeException extends RuntimeException {
  }

  public static class MachineAreaDataIntegrityException extends RuntimeException {
  }

  public static class MachineAreaMutationForbiddenException extends RuntimeException {
  }

  public static class MachineAreaNotFoundException extends RuntimeException {
  }

  public static class MachineAreaHasMachinesException extends RuntimeException {
  }

  public static class PlantNotFoundForMachineAreaException extends RuntimeException {
  }

  /** Audit snapshot helper for a machine area. */
  private static final class MachineAreaAuditValues {
    private MachineAreaAuditValues() {
    }

    static Map<String, Object> of(MachineAreaEntity area) {
      var values = new LinkedHashMap<String, Object>();
      values.put("plantId", area.getPlantId().toString());
      values.put("code", area.getCode());
      values.put("name", area.getName());
      values.put("description", area.getDescription());
      values.put("active", area.isActive());
      return values;
    }
  }
}
