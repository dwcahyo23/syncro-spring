package com.syncro.org.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.org.infrastructure.DepartmentEntity;
import com.syncro.org.infrastructure.DepartmentMemberEntity;
import com.syncro.org.infrastructure.DepartmentMemberRepository;
import com.syncro.org.infrastructure.DepartmentRepository;
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
 * Organization-maintenance departments (people units, plant-scoped). CRUD plus a
 * wholesale member replace (mirrors reference {@code setDepartmentUsers}). SPV/MG
 * leaders must be active users with access to the same plant. Departments are
 * soft-inactivated — never hard-deleted — and deactivation is rejected while the
 * department still has members. Audit is the Phase 1 actor-correlated model
 * ({@code DEPARTMENT} / {@code DEPARTMENT_MEMBER} entity types).
 */
@Service
public class DepartmentService {

  private static final String DEPARTMENT_NAME_UNIQUE_CONSTRAINT = "uq_departments_plant_name";
  private static final String DEPARTMENT_MEMBER_UNIQUE_CONSTRAINT = "uq_department_members";

  private final DepartmentRepository departments;
  private final DepartmentMemberRepository members;
  private final PlantRepository plants;
  private final AuthUserRepository users;
  private final AuthUserPlantAssignmentRepository assignments;
  private final PlantScopeService plantScopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public DepartmentService(
      DepartmentRepository departments,
      DepartmentMemberRepository members,
      PlantRepository plants,
      AuthUserRepository users,
      AuthUserPlantAssignmentRepository assignments,
      PlantScopeService plantScopes,
      AuditLogWriter auditLog,
      Clock clock) {
    this.departments = departments;
    this.members = members;
    this.plants = plants;
    this.users = users;
    this.assignments = assignments;
    this.plantScopes = plantScopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public DepartmentListView list(AuthenticatedUser user, UUID plantId, boolean includeInactive) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    } else if (!plants.existsById(plantId)) {
      throw new PlantNotFoundForDepartmentException();
    }
    var activeOnly = !includeInactive;
    var result = departments.findAllByPlantId(plantId, activeOnly);
    return new DepartmentListView(result.stream()
        .map(department -> toView(department, members.countByDepartmentId(department.getId())))
        .toList());
  }

  @Transactional(readOnly = true)
  public DepartmentView get(AuthenticatedUser user, UUID departmentId) {
    var department = findScoped(user, departmentId);
    return toView(department, members.countByDepartmentId(departmentId));
  }

  @Transactional
  public DepartmentView create(AuthenticatedUser user, CreateDepartmentCommand command) {
    requireMutationRole(user);
    var plantId = command.plantId();
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    }
    var plant = plants.findById(plantId).orElseThrow(PlantNotFoundForDepartmentException::new);
    var name = normalizeName(command.name());
    validateLeaders(plantId, command.spvId(), command.mgId());
    if (departments.existsByPlantIdAndNameIgnoreCase(plantId, name)) {
      throw new DuplicateDepartmentNameException();
    }
    var now = Instant.now(clock);
    var saved = saveDepartment(new DepartmentEntity(UUID.randomUUID(), plant, name, command.spvId(), command.mgId(),
        true, now, now));
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.DEPARTMENT, saved.getId(),
        saved.getName(), plantId, null, DepartmentAuditValues.of(saved), null));
    return toView(saved, 0);
  }

  @Transactional
  public DepartmentView update(AuthenticatedUser user, UUID departmentId, UpdateDepartmentCommand command) {
    requireMutationRole(user);
    var department = findScoped(user, departmentId);
    var plantId = department.getPlant().getId();
    var entityLabel = department.getName();
    var previous = DepartmentAuditValues.of(department);
    var name = normalizeName(command.name());
    validateLeaders(plantId, command.spvId(), command.mgId());
    if (departments.findByPlantIdAndNameIgnoreCase(plantId, name)
        .filter(existing -> !existing.getId().equals(departmentId))
        .isPresent()) {
      throw new DuplicateDepartmentNameException();
    }
    department.update(name, command.spvId(), command.mgId(), command.active(), Instant.now(clock));
    var saved = saveDepartment(department);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.DEPARTMENT, departmentId, entityLabel,
        plantId, previous, DepartmentAuditValues.of(saved), null));
    return toView(saved, members.countByDepartmentId(departmentId));
  }

  /**
   * Deactivate-style delete: reject when the department still has members
   * (409 {@code DEPARTMENT_HAS_MEMBERS}); otherwise soft-inactivate. No hard delete.
   */
  @Transactional
  public void delete(AuthenticatedUser user, UUID departmentId) {
    requireMutationRole(user);
    var department = findScoped(user, departmentId);
    if (members.countByDepartmentId(departmentId) > 0) {
      throw new DepartmentHasMembersException();
    }
    var entityLabel = department.getName();
    var previous = DepartmentAuditValues.of(department);
    department.deactivate(Instant.now(clock));
    var saved = saveDepartment(department);
    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.DEPARTMENT, departmentId, entityLabel,
        department.getPlant().getId(), previous, DepartmentAuditValues.of(saved), null));
  }

  /**
   * Full member replace (mirrors reference {@code setDepartmentUsers}). Only active
   * departments accept member changes. Technicians may belong to multiple departments
   * (uniqueness is {@code (department_id, user_id)}).
   */
  @Transactional
  public DepartmentView replaceMembers(AuthenticatedUser user, UUID departmentId, List<UUID> userIds) {
    requireMutationRole(user);
    var department = findScoped(user, departmentId);
    if (!department.isActive()) {
      throw new DepartmentInactiveException();
    }
    var plantId = department.getPlant().getId();
    var entityLabel = department.getName();
    var previous = new LinkedHashMap<String, Object>();
    previous.put("action", "membersReplaced");
    previous.put("memberCount", members.countByDepartmentId(departmentId));

    // Validate every requested user before mutating so an unknown id cannot leave
    // the department partially replaced (transactional all-or-nothing).
    var uniqueUserIds = new java.util.HashSet<>(userIds);
    for (var userId : uniqueUserIds) {
      if (!users.existsById(userId)) {
        throw new UserNotFoundException();
      }
    }

    // Delete existing members then re-insert the requested set (full replace).
    members.deleteByDepartmentId(departmentId);
    members.flush();
    var now = Instant.now(clock);
    var actorId = UUID.fromString(user.id());
    var inserted = 0;
    for (var userId : uniqueUserIds) {
      members.saveAndFlush(
          new DepartmentMemberEntity(UUID.randomUUID(), departmentId, userId, actorId, now));
      inserted++;
    }
    var current = new LinkedHashMap<String, Object>();
    current.put("action", "membersReplaced");
    current.put("memberCount", inserted);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.DEPARTMENT, departmentId, entityLabel,
        plantId, previous, current, null));
    return toView(department, inserted);
  }

  private DepartmentEntity findScoped(AuthenticatedUser user, UUID departmentId) {
    var department = departments.findByIdWithPlant(departmentId).orElseThrow(DepartmentNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, department.getPlant().getId());
    }
    return department;
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN
        && user.applicationRole() != ApplicationRole.MANAGER_MAINTENANCE) {
      throw new DepartmentMutationForbiddenException();
    }
  }

  /** SPV/MG must be existing, enabled users with access to the department's plant. */
  private void validateLeaders(UUID plantId, UUID spvId, UUID mgId) {
    validateLeader(plantId, spvId, "spvId");
    validateLeader(plantId, mgId, "mgId");
  }

  private void validateLeader(UUID plantId, UUID leaderId, String field) {
    if (leaderId == null) {
      return;
    }
    var leader = users.findById(leaderId).orElseThrow(UserNotFoundException::new);
    if (!leader.isEnabled()) {
      throw new LeaderValidationException(field);
    }
    // The leader must have access to the department's plant (same-plant rule).
    // SUPER_ADMIN users are always treated as having plant access.
    var hasPlantAccess = leader.getApplicationRole() == ApplicationRole.SUPER_ADMIN
        || assignments.findByAuthUserId(leaderId).stream()
            .anyMatch(assignment -> assignment.getPlantId().equals(plantId));
    if (!hasPlantAccess) {
      throw new LeaderValidationException(field);
    }
  }

  private DepartmentEntity saveDepartment(DepartmentEntity department) {
    try {
      return departments.saveAndFlush(department);
    } catch (DataIntegrityViolationException exception) {
      if (isUniqueViolation(exception, DEPARTMENT_NAME_UNIQUE_CONSTRAINT)) {
        throw new DuplicateDepartmentNameException();
      }
      throw new DepartmentDataIntegrityException();
    }
  }

  private boolean isUniqueViolation(DataIntegrityViolationException exception, String constraint) {
    var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
    return message.contains(constraint);
  }

  private String normalizeName(String name) {
    return name == null ? null : name.trim();
  }

  private DepartmentView toView(DepartmentEntity department, long memberCount) {
    return new DepartmentView(
        department.getId(),
        department.getPlant().getId(),
        department.getPlant().getCode(),
        department.getPlant().getName(),
        department.getName(),
        department.getSpvId(),
        department.getMgId(),
        department.isActive(),
        memberCount,
        department.getCreatedAt(),
        department.getUpdatedAt());
  }

  public record CreateDepartmentCommand(UUID plantId, String name, UUID spvId, UUID mgId) {
  }

  public record UpdateDepartmentCommand(String name, UUID spvId, UUID mgId, Boolean active) {
  }

  public record DepartmentView(
      UUID id,
      UUID plantId,
      String plantCode,
      String plantName,
      String name,
      UUID spvId,
      UUID mgId,
      boolean active,
      long memberCount,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record DepartmentListView(List<DepartmentView> items) {
  }

  public record DepartmentMemberView(UUID userId, String loginIdentifier, String displayName) {
  }

  public static class DuplicateDepartmentNameException extends RuntimeException {
  }

  public static class DepartmentDataIntegrityException extends RuntimeException {
  }

  public static class DepartmentMutationForbiddenException extends RuntimeException {
  }

  public static class DepartmentNotFoundException extends RuntimeException {
  }

  public static class PlantNotFoundForDepartmentException extends RuntimeException {
  }

  public static class DepartmentHasMembersException extends RuntimeException {
  }

  public static class DepartmentInactiveException extends RuntimeException {
  }

  public static class UserNotFoundException extends RuntimeException {
  }

  /** SPV/MG is not an enabled user with access to the department's plant. */
  public static class LeaderValidationException extends RuntimeException {
    private final String field;

    public LeaderValidationException(String field) {
      this.field = field;
    }

    public String getField() {
      return field;
    }
  }

  /** Audit snapshot helper for a department. */
  private static final class DepartmentAuditValues {
    private DepartmentAuditValues() {
    }

    static Map<String, Object> of(DepartmentEntity department) {
      var values = new LinkedHashMap<String, Object>();
      values.put("plantCode", department.getPlant().getCode());
      values.put("plantName", department.getPlant().getName());
      values.put("name", department.getName());
      values.put("spvId", department.getSpvId() == null ? null : department.getSpvId().toString());
      values.put("mgId", department.getMgId() == null ? null : department.getMgId().toString());
      values.put("active", department.isActive());
      return values;
    }
  }
}
