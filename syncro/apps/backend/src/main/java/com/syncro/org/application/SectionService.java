package com.syncro.org.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.org.domain.SectionType;
import com.syncro.org.infrastructure.SectionEntity;
import com.syncro.org.infrastructure.SectionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sections CRUD (per plant, {@code MACHINERY|UTILITY|WORKSHOP}). Role gate is the
 * Phase 1 {@code SUPER_ADMIN|MANAGE} pattern; MANAGE additionally needs plant
 * access. No DELETE — deactivation is a PUT with {@code active=false}, guarded by
 * {@link SectionActiveMachineGroupReader} so a section with an ACTIVE-machine group
 * cannot be deactivated. Audit follows the Phase 1 actor-correlated model.
 */
@Service
public class SectionService {

  private static final String SECTION_CODE_UNIQUE_CONSTRAINT = "uq_sections_plant_code";
  private static final String SECTION_NAME_UNIQUE_CONSTRAINT = "uq_sections_plant_id_lower_name";

  private final SectionRepository sections;
  private final PlantRepository plants;
  private final PlantScopeService plantScopes;
  private final SectionActiveMachineGroupReader activeMachineGroupReader;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public SectionService(
      SectionRepository sections,
      PlantRepository plants,
      PlantScopeService plantScopes,
      SectionActiveMachineGroupReader activeMachineGroupReader,
      AuditLogWriter auditLog,
      Clock clock) {
    this.sections = sections;
    this.plants = plants;
    this.plantScopes = plantScopes;
    this.activeMachineGroupReader = activeMachineGroupReader;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public SectionListView list(AuthenticatedUser user, UUID plantId, boolean includeInactive) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    } else if (!plants.existsById(plantId)) {
      throw new PlantNotFoundForSectionException();
    }
    var activeOnly = !includeInactive;
    var result = sections.findAllByPlantId(plantId, activeOnly);
    return new SectionListView(result.stream().map(this::toView).toList());
  }

  @Transactional(readOnly = true)
  public SectionView get(AuthenticatedUser user, UUID sectionId) {
    return toView(findScoped(user, sectionId));
  }

  @Transactional
  public SectionView create(AuthenticatedUser user, CreateSectionCommand command) {
    requireMutationRole(user);
    var plantId = command.plantId();
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    }
    var plant = plants.findById(plantId).orElseThrow(PlantNotFoundForSectionException::new);
    var code = normalizeCode(command.code());
    var name = normalizeName(command.name());
    if (sections.findByPlantIdAndCodeIgnoreCase(plantId, code).isPresent()) {
      throw new DuplicateSectionCodeException();
    }
    if (sections.existsByPlantIdAndNameIgnoreCase(plantId, name)) {
      throw new DuplicateSectionNameException();
    }
    var now = Instant.now(clock);
    var saved = saveSection(new SectionEntity(UUID.randomUUID(), plant, code, name, true, now, now));
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.SECTION, saved.getId(),
        saved.getCode(), plantId, null, SectionAuditValues.of(saved)));
    return toView(saved);
  }

  @Transactional
  public SectionView update(AuthenticatedUser user, UUID sectionId, UpdateSectionCommand command) {
    requireMutationRole(user);
    var section = findScoped(user, sectionId);
    var plantId = section.getPlant().getId();
    var entityLabel = section.getCode();
    var previous = SectionAuditValues.of(section);
    var name = normalizeName(command.name());
    if (sections.findByPlantIdAndNameIgnoreCase(plantId, name)
        .filter(existing -> !existing.getId().equals(sectionId))
        .isPresent()) {
      throw new DuplicateSectionNameException();
    }
    var deactivating = section.isActive() && !command.active();
    if (deactivating && activeMachineGroupReader.hasActiveMachineGroup(sectionId)) {
      throw new SectionHasActiveMachineGroupsException();
    }
    section.update(name, command.active(), Instant.now(clock));
    var saved = saveSection(section);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.SECTION, sectionId, entityLabel,
        plantId, previous, SectionAuditValues.of(saved)));
    return toView(saved);
  }

  private SectionEntity findScoped(AuthenticatedUser user, UUID sectionId) {
    var section = sections.findByIdWithPlant(sectionId).orElseThrow(SectionNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, section.getPlant().getId());
    }
    return section;
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN && user.applicationRole() != ApplicationRole.MANAGE) {
      throw new SectionMutationForbiddenException();
    }
  }

  private SectionEntity saveSection(SectionEntity section) {
    try {
      return sections.saveAndFlush(section);
    } catch (DataIntegrityViolationException exception) {
      if (isUniqueViolation(exception, SECTION_CODE_UNIQUE_CONSTRAINT)) {
        throw new DuplicateSectionCodeException();
      }
      if (isUniqueViolation(exception, SECTION_NAME_UNIQUE_CONSTRAINT)) {
        throw new DuplicateSectionNameException();
      }
      throw new SectionDataIntegrityException();
    }
  }

  private boolean isUniqueViolation(DataIntegrityViolationException exception, String constraint) {
    var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
    return message.contains(constraint);
  }

  private String normalizeName(String name) {
    return name.trim();
  }

  /** Uppercases and validates the section code against the {@link SectionType} contract. */
  private String normalizeCode(String code) {
    if (code == null) {
      throw new InvalidSectionCodeException();
    }
    try {
      return SectionType.valueOf(code.trim().toUpperCase()).name();
    } catch (IllegalArgumentException invalid) {
      throw new InvalidSectionCodeException();
    }
  }

  private SectionView toView(SectionEntity section) {
    var plant = section.getPlant();
    return new SectionView(
        section.getId(),
        plant.getId(),
        plant.getCode(),
        plant.getName(),
        section.getCode(),
        section.getName(),
        section.isActive(),
        section.getCreatedAt(),
        section.getUpdatedAt());
  }

  public record CreateSectionCommand(UUID plantId, String code, String name) {
  }

  public record UpdateSectionCommand(String name, boolean active) {
  }

  public record SectionView(
      UUID id,
      UUID plantId,
      String plantCode,
      String plantName,
      String code,
      String name,
      boolean active,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record SectionListView(List<SectionView> items) {
  }

  public static class DuplicateSectionCodeException extends RuntimeException {
  }

  public static class DuplicateSectionNameException extends RuntimeException {
  }

  public static class SectionDataIntegrityException extends RuntimeException {
  }

  public static class SectionHasActiveMachineGroupsException extends RuntimeException {
  }

  public static class SectionMutationForbiddenException extends RuntimeException {
  }

  public static class SectionNotFoundException extends RuntimeException {
  }

  public static class InvalidSectionCodeException extends RuntimeException {
  }

  public static class PlantNotFoundForSectionException extends RuntimeException {
  }
}
