package com.syncro.org.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.org.application.DepartmentService.CreateDepartmentCommand;
import com.syncro.org.application.DepartmentService.DepartmentHasMembersException;
import com.syncro.org.application.DepartmentService.DepartmentNotFoundException;
import com.syncro.org.application.DepartmentService.DuplicateDepartmentNameException;
import com.syncro.org.application.DepartmentService.LeaderValidationException;
import com.syncro.org.application.DepartmentService.UserNotFoundException;
import com.syncro.org.infrastructure.DepartmentEntity;
import com.syncro.org.infrastructure.DepartmentMemberEntity;
import com.syncro.org.infrastructure.DepartmentMemberRepository;
import com.syncro.org.infrastructure.DepartmentRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class DepartmentServiceTest {
  @Mock
  private DepartmentRepository departments;
  @Mock
  private DepartmentMemberRepository members;
  @Mock
  private PlantRepository plants;
  @Mock
  private AuthUserRepository users;
  @Mock
  private AuthUserPlantAssignmentRepository assignments;
  @Mock
  private PlantScopeService plantScopes;
  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void createPersistsDepartmentWithLeaders() {
    var plantId = UUID.randomUUID();
    var spvId = UUID.randomUUID();
    var plant = new PlantEntity(plantId, "GM1", "Plant GM1", Instant.now(clock), Instant.now(clock));
    when(plants.findById(plantId)).thenReturn(Optional.of(plant));
    when(users.findById(spvId)).thenReturn(Optional.of(activeUser(spvId)));
    when(assignments.findByAuthUserId(spvId)).thenReturn(List.of(assignment(spvId, plantId)));
    when(departments.existsByPlantIdAndNameIgnoreCase(plantId, "Mechanical")).thenReturn(false);
    when(departments.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var service = new DepartmentService(departments, members, plants, users, assignments, plantScopes, auditLog, clock);
    var created = service.create(user(ApplicationRole.MANAGER_MAINTENANCE),
        new CreateDepartmentCommand(plantId, "Mechanical", spvId, null));

    assertThat(created.name()).isEqualTo("Mechanical");
    assertThat(created.spvId()).isEqualTo(spvId);
    assertThat(created.active()).isTrue();
  }

  @Test
  void createRejectsDuplicatePlantName() {
    var plantId = UUID.randomUUID();
    var plant = new PlantEntity(plantId, "GM1", "Plant GM1", Instant.now(clock), Instant.now(clock));
    when(plants.findById(plantId)).thenReturn(Optional.of(plant));
    when(departments.existsByPlantIdAndNameIgnoreCase(plantId, "Mechanical")).thenReturn(true);

    var service = new DepartmentService(departments, members, plants, users, assignments, plantScopes, auditLog, clock);
    assertThatThrownBy(() -> service.create(user(ApplicationRole.MANAGER_MAINTENANCE),
        new CreateDepartmentCommand(plantId, "Mechanical", null, null)))
        .isInstanceOf(DuplicateDepartmentNameException.class);
  }

  @Test
  void createRejectsSpvInAnotherPlant() {
    var plantId = UUID.randomUUID();
    var spvId = UUID.randomUUID();
    var otherPlantId = UUID.randomUUID();
    var plant = new PlantEntity(plantId, "GM1", "Plant GM1", Instant.now(clock), Instant.now(clock));
    when(plants.findById(plantId)).thenReturn(Optional.of(plant));
    when(users.findById(spvId)).thenReturn(Optional.of(activeUser(spvId)));
    // spv is assigned to another plant only
    when(assignments.findByAuthUserId(spvId)).thenReturn(List.of(assignment(spvId, otherPlantId)));

    var service = new DepartmentService(departments, members, plants, users, assignments, plantScopes, auditLog, clock);
    assertThatThrownBy(() -> service.create(user(ApplicationRole.MANAGER_MAINTENANCE),
        new CreateDepartmentCommand(plantId, "Mechanical", spvId, null)))
        .isInstanceOf(LeaderValidationException.class);
  }

  @Test
  void createRejectsUnknownUser() {
    var plantId = UUID.randomUUID();
    var spvId = UUID.randomUUID();
    var plant = new PlantEntity(plantId, "GM1", "Plant GM1", Instant.now(clock), Instant.now(clock));
    when(plants.findById(plantId)).thenReturn(Optional.of(plant));
    when(users.findById(spvId)).thenReturn(Optional.empty());

    var service = new DepartmentService(departments, members, plants, users, assignments, plantScopes, auditLog, clock);
    assertThatThrownBy(() -> service.create(user(ApplicationRole.MANAGER_MAINTENANCE),
        new CreateDepartmentCommand(plantId, "Mechanical", spvId, null)))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  void createMapsUniqueNameViolationToDuplicate() {
    var plantId = UUID.randomUUID();
    var plant = new PlantEntity(plantId, "GM1", "Plant GM1", Instant.now(clock), Instant.now(clock));
    when(plants.findById(plantId)).thenReturn(Optional.of(plant));
    when(departments.existsByPlantIdAndNameIgnoreCase(plantId, "Mechanical")).thenReturn(false);
    when(departments.saveAndFlush(any())).thenThrow(uniqueViolation("uq_departments_plant_name"));

    var service = new DepartmentService(departments, members, plants, users, assignments, plantScopes, auditLog, clock);
    assertThatThrownBy(() -> service.create(user(ApplicationRole.MANAGER_MAINTENANCE),
        new CreateDepartmentCommand(plantId, "Mechanical", null, null)))
        .isInstanceOf(DuplicateDepartmentNameException.class);
  }

  @Test
  void deleteRejectsWhenMembersExist() {
    var departmentId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var department = department(departmentId, plantId, true);
    when(departments.findByIdWithPlant(departmentId)).thenReturn(Optional.of(department));
    when(members.countByDepartmentId(departmentId)).thenReturn(3L);

    var service = new DepartmentService(departments, members, plants, users, assignments, plantScopes, auditLog, clock);
    assertThatThrownBy(() -> service.delete(user(ApplicationRole.MANAGER_MAINTENANCE), departmentId))
        .isInstanceOf(DepartmentHasMembersException.class);
  }

  @Test
  void deleteSoftInactivatesWhenNoMembers() {
    var departmentId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var department = department(departmentId, plantId, true);
    when(departments.findByIdWithPlant(departmentId)).thenReturn(Optional.of(department));
    when(members.countByDepartmentId(departmentId)).thenReturn(0L);
    when(departments.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var service = new DepartmentService(departments, members, plants, users, assignments, plantScopes, auditLog, clock);
    service.delete(user(ApplicationRole.MANAGER_MAINTENANCE), departmentId);

    assertThat(department.isActive()).isFalse();
  }

  @Test
  void getUnknownDepartmentThrowsNotFound() {
    var departmentId = UUID.randomUUID();
    when(departments.findByIdWithPlant(departmentId)).thenReturn(Optional.empty());

    var service = new DepartmentService(departments, members, plants, users, assignments, plantScopes, auditLog, clock);
    assertThatThrownBy(() -> service.get(user(ApplicationRole.MANAGER_MAINTENANCE), departmentId))
        .isInstanceOf(DepartmentNotFoundException.class);
  }

  @Test
  void replaceMembersRejectsInactiveDepartment() {
    var departmentId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var department = department(departmentId, plantId, false);
    when(departments.findByIdWithPlant(departmentId)).thenReturn(Optional.of(department));

    var service = new DepartmentService(departments, members, plants, users, assignments, plantScopes, auditLog, clock);
    assertThatThrownBy(() -> service.replaceMembers(user(ApplicationRole.MANAGER_MAINTENANCE), departmentId,
        List.of(UUID.randomUUID())))
        .isInstanceOf(DepartmentService.DepartmentInactiveException.class);
  }

  @Test
  void replaceMembersDeletesThenReinserts() {
    var departmentId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var userId1 = UUID.randomUUID();
    var department = department(departmentId, plantId, true);
    when(departments.findByIdWithPlant(departmentId)).thenReturn(Optional.of(department));
    when(members.countByDepartmentId(departmentId)).thenReturn(1L);
    when(users.existsById(userId1)).thenReturn(true);
    when(members.saveAndFlush(any(DepartmentMemberEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var service = new DepartmentService(departments, members, plants, users, assignments, plantScopes, auditLog, clock);
    var view = service.replaceMembers(user(ApplicationRole.MANAGER_MAINTENANCE), departmentId, List.of(userId1));

    assertThat(view.memberCount()).isEqualTo(1);
  }

  private static DepartmentEntity department(UUID id, UUID plantId, boolean active) {
    return new DepartmentEntity(id, new PlantEntity(plantId, "GM1", "Plant GM1",
        Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-08-27T00:00:00Z")),
        "Mechanical", null, null, active,
        Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-08-27T00:00:00Z"));
  }

  private static AuthUserEntity activeUser(UUID id) {
    var now = Instant.parse("2026-08-27T00:00:00Z");
    var user = new AuthUserEntity(id, "user-" + id + "@syncro.dev", "hash", ApplicationRole.TECHNICIAN, true, now, now);
    user.updateMasterFields("User " + id, null, null, null, null, now);
    return user;
  }

  private static AuthUserPlantAssignmentEntity assignment(UUID userId, UUID plantId) {
    return new AuthUserPlantAssignmentEntity(userId, plantId, Instant.parse("2026-08-27T00:00:00Z"));
  }

  private static DataIntegrityViolationException uniqueViolation(String constraintName) {
    return new DataIntegrityViolationException(
        "could not execute statement",
        new SQLException("ERROR: duplicate key value violates unique constraint \"" + constraintName + "\""));
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }
}
