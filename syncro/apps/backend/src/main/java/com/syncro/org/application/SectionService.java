package com.syncro.org.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
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
 * Phase 1 mutation-gate pattern ({@code SUPER_ADMIN|MANAGER_MAINTENANCE});
 * MANAGER_MAINTENANCE additionally needs plant access. No DELETE — deactivation is a PUT with {@code active=false}, guarded by
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
  private final AuthUserRepository users;
  private final MachineGroupRepository machineGroups;
  private final MachineRepository machines;
  private final MachineResponsibilityRepository responsibilities;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public SectionService(
      SectionRepository sections,
      PlantRepository plants,
      PlantScopeService plantScopes,
      SectionActiveMachineGroupReader activeMachineGroupReader,
      AuthUserRepository users,
      MachineGroupRepository machineGroups,
      MachineRepository machines,
      MachineResponsibilityRepository responsibilities,
      AuditLogWriter auditLog,
      Clock clock) {
    this.sections = sections;
    this.plants = plants;
    this.plantScopes = plantScopes;
    this.activeMachineGroupReader = activeMachineGroupReader;
    this.users = users;
    this.machineGroups = machineGroups;
    this.machines = machines;
    this.responsibilities = responsibilities;
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
        saved.getCode(), plantId, null, SectionAuditValues.of(saved), null));
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
        plantId, previous, SectionAuditValues.of(saved), null));
    return toView(saved);
  }

  /**
   * Assign an explicit section leader (D2a). Stores {@code sections.leader_user_id}
   * AND auto-creates/updates a LEADER {@code machine_responsibilities} row for that
   * user on every machine in the section's machine groups — the side-effect that
   * keeps the derived section-leader scope ({@link SectionLeaderMachineGroupReader})
   * consistent with the stored leader. Clearing removes both.
   */
  @Transactional
  public SectionLeaderView assignLeader(AuthenticatedUser user, UUID sectionId, UUID leaderUserId) {
    requireMutationRole(user);
    var section = findScopedForUpdate(user, sectionId);
    var leader = users.findById(leaderUserId).orElseThrow(SectionLeaderUserNotFoundException::new);
    if (!leader.isEnabled()) {
      throw new SectionLeaderUserInactiveException();
    }
    var plantId = section.getPlant().getId();
    var entityLabel = section.getCode();
    var previous = SectionAuditValues.of(section);
    section.assignLeader(leaderUserId, Instant.now(clock));
    var saved = sections.saveAndFlush(section);
    upsertLeaderResponsibilities(section.getId(), leaderUserId);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.SECTION, sectionId, entityLabel,
        plantId, previous, SectionAuditValues.of(saved), null));
    return new SectionLeaderView(sectionId, leaderUserId);
  }

  @Transactional
  public void clearLeader(AuthenticatedUser user, UUID sectionId) {
    requireMutationRole(user);
    var section = findScopedForUpdate(user, sectionId);
    if (section.getLeaderUserId() == null) {
      return;
    }
    var leaderUserId = section.getLeaderUserId();
    var plantId = section.getPlant().getId();
    var entityLabel = section.getCode();
    var previous = SectionAuditValues.of(section);
    section.clearLeader(Instant.now(clock));
    var saved = sections.saveAndFlush(section);
    removeLeaderResponsibilities(section.getId(), leaderUserId);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.SECTION, sectionId, entityLabel,
        plantId, previous, SectionAuditValues.of(saved), null));
  }

  /** LEADER responsibilities across every machine in the section's machine groups. */
  private void upsertLeaderResponsibilities(UUID sectionId, UUID leaderUserId) {
    var groupIds = machineGroups.findIdsBySectionId(sectionId);
    if (groupIds.isEmpty()) {
      return;
    }
    var machineIds = machines.findIdsByMachineGroupIdIn(groupIds);
    if (machineIds.isEmpty()) {
      return;
    }
    var existing = responsibilities.findAssignments(machineIds, leaderUserId, ResponsibilityLevel.LEADER);
    var existingByMachineId = existing.stream()
        .collect(java.util.stream.Collectors.toMap(MachineResponsibilityEntity::getMachineId, r -> r));
    var now = Instant.now(clock);
    for (var machineId : machineIds) {
      var row = existingByMachineId.get(machineId);
      if (row == null) {
        responsibilities.save(new MachineResponsibilityEntity(
            UUID.randomUUID(), machineId, leaderUserId, ResponsibilityLevel.LEADER, now, now));
      } else {
        row.update(ResponsibilityLevel.LEADER, now);
        responsibilities.save(row);
      }
    }
  }

  /** Removes the LEADER responsibilities for the section's machines (clear side-effect). */
  private void removeLeaderResponsibilities(UUID sectionId, UUID leaderUserId) {
    var groupIds = machineGroups.findIdsBySectionId(sectionId);
    if (groupIds.isEmpty()) {
      return;
    }
    var machineIds = machines.findIdsByMachineGroupIdIn(groupIds);
    if (machineIds.isEmpty()) {
      return;
    }
    responsibilities.deleteByMachineIdInAndUserId(machineIds, leaderUserId);
  }

  private SectionEntity findScopedForUpdate(AuthenticatedUser user, UUID sectionId) {
    var section = sections.findByIdForUpdate(sectionId).orElseThrow(SectionNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, section.getPlant().getId());
    }
    return section;
  }

  private SectionEntity findScoped(AuthenticatedUser user, UUID sectionId) {
    var section = sections.findByIdWithPlant(sectionId).orElseThrow(SectionNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, section.getPlant().getId());
    }
    return section;
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN && user.applicationRole() != ApplicationRole.MANAGER_MAINTENANCE) {
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
        section.getLeaderUserId(),
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
      UUID leaderUserId,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record SectionListView(List<SectionView> items) {
  }

  public record SectionLeaderView(UUID sectionId, UUID leaderUserId) {
  }

  public static class SectionLeaderUserNotFoundException extends RuntimeException {
  }

  public static class SectionLeaderUserInactiveException extends RuntimeException {
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
