package com.syncro.machine.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;

import static org.assertj.core.api.Assertions.assertThat;

class MachineResponsibilityServiceIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired
  private MachineResponsibilityService responsibilityService;

  @Autowired
  private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  @Autowired
  private MachineResponsibilityRepository responsibilityRepository;

  @Autowired
  private MachineRepository machines;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

  @jakarta.persistence.PersistenceContext
  private jakarta.persistence.EntityManager entityManager;

  @Test
  @org.junit.jupiter.api.DisplayName("2.7-SVC-001 P0 should store machine responsibility mapping successfully")
  void shouldStoreMapping() {
      // Given machine and user exist
      var plant = plant("GM1", "Plant GM1");
      var group = group(plant, "Forming");
      var machine = machine(plant, group, "BF-08410");
      var userToAssign = persistedAuthUser(com.syncro.auth.domain.ApplicationRole.VIEWER, "assigned-user@syncro.dev");
      var admin = authenticatedUser(com.syncro.auth.domain.ApplicationRole.SUPER_ADMIN);

      // When assigned
      var request = new com.syncro.machine.api.MachineResponsibilityDtos.CreateMachineResponsibilityRequest(
          machine.getId(), UUID.fromString(userToAssign.id()), com.syncro.machine.domain.ResponsibilityLevel.TECHNICIAN
      );
      var response = responsibilityService.assign(admin, request);

      // Then mapping is stored correctly with level
      assertThat(response.level()).isEqualTo(com.syncro.machine.domain.ResponsibilityLevel.TECHNICIAN);
      var entity = responsibilityRepository.findById(response.id()).orElseThrow();
      assertThat(entity.getMachine().getId()).isEqualTo(machine.getId());
      assertThat(entity.getUser().getId()).isEqualTo(UUID.fromString(userToAssign.id()));
      assertThat(entity.getLevel()).isEqualTo(com.syncro.machine.domain.ResponsibilityLevel.TECHNICIAN);
  }

  @Test
  @org.junit.jupiter.api.DisplayName("2.7-SVC-002 P0 should filter by plant scope for MANAGE/VIEWER roles")
  void shouldEnforcePlantScope() {
      // Given user has limited plant access
      var assignedPlant = plant("GM1", "Plant GM1");
      var otherPlant = plant("GM2", "Plant GM2");
      var assignedGroup = group(assignedPlant, "Forming");
      var otherGroup = group(otherPlant, "Packing");
      var assignedMachine = machine(assignedPlant, assignedGroup, "BF-08410");
      var otherMachine = machine(otherPlant, otherGroup, "PK-001");
      
      var targetUser = persistedAuthUser(com.syncro.auth.domain.ApplicationRole.VIEWER, "assigned-user-scope@syncro.dev");
      
      var admin = authenticatedUser(com.syncro.auth.domain.ApplicationRole.SUPER_ADMIN);
      responsibilityService.assign(admin, new com.syncro.machine.api.MachineResponsibilityDtos.CreateMachineResponsibilityRequest(assignedMachine.getId(), UUID.fromString(targetUser.id()), com.syncro.machine.domain.ResponsibilityLevel.TECHNICIAN));
      responsibilityService.assign(admin, new com.syncro.machine.api.MachineResponsibilityDtos.CreateMachineResponsibilityRequest(otherMachine.getId(), UUID.fromString(targetUser.id()), com.syncro.machine.domain.ResponsibilityLevel.TECHNICIAN));

      var manageUser = persistedAuthUser(com.syncro.auth.domain.ApplicationRole.MANAGE, "manage-machine@syncro.dev");
      assignPlant(manageUser, assignedPlant);

      // When retrieving machine responsibilities
      var result = responsibilityService.listAll(manageUser, org.springframework.data.domain.Pageable.unpaged());

      // Then only those in user's plants are returned
      assertThat(result.getContent()).hasSize(1);
      assertThat(result.getContent().get(0).machineId()).isEqualTo(assignedMachine.getId());
  }

  @Test
  @org.junit.jupiter.api.DisplayName("2.7-SVC-003 P1 should cascade delete when machine is removed")
  void shouldCascadeDeleteOnMachineRemoval() {
      // Given mapping exists
      var plant = plant("GM1", "Plant GM1");
      var group = group(plant, "Forming");
      var machine = machine(plant, group, "BF-08410");
      var targetUser = persistedAuthUser(com.syncro.auth.domain.ApplicationRole.VIEWER, "assigned-user-cascade@syncro.dev");
      var admin = authenticatedUser(com.syncro.auth.domain.ApplicationRole.SUPER_ADMIN);
      
      var response = responsibilityService.assign(admin, new com.syncro.machine.api.MachineResponsibilityDtos.CreateMachineResponsibilityRequest(
          machine.getId(), UUID.fromString(targetUser.id()), com.syncro.machine.domain.ResponsibilityLevel.TECHNICIAN
      ));

      responsibilityRepository.flush();
      
      // When machine is deleted directly via JDBC to test DB cascade without JPA cache interference
      jdbcTemplate.update("DELETE FROM machines WHERE id = ?", machine.getId());
      entityManager.clear();

      // Then responsibility record is removed
      assertThat(responsibilityRepository.findById(response.id())).isEmpty();
  }

  private com.syncro.auth.infrastructure.PlantEntity plant(String code, String name) {
    var now = java.time.Instant.parse("2026-05-27T00:00:00Z");
    return plants.saveAndFlush(new com.syncro.auth.infrastructure.PlantEntity(UUID.randomUUID(), code, name, now, now));
  }

  private com.syncro.masterdata.infrastructure.MachineGroupEntity group(com.syncro.auth.infrastructure.PlantEntity plant, String name) {
    var now = java.time.Instant.parse("2026-05-27T00:00:00Z");
    return machineGroups.saveAndFlush(new com.syncro.masterdata.infrastructure.MachineGroupEntity(UUID.randomUUID(), plant, name, now, now));
  }

  private com.syncro.machine.infrastructure.MachineEntity machine(com.syncro.auth.infrastructure.PlantEntity plant, com.syncro.masterdata.infrastructure.MachineGroupEntity group, String code) {
    var now = java.time.Instant.parse("2026-05-27T00:00:00Z");
    return machines.saveAndFlush(new com.syncro.machine.infrastructure.MachineEntity(UUID.randomUUID(), plant, group, code, "Name", com.syncro.machine.domain.MachineStatus.ACTIVE, "Brand", null, null, java.util.List.of(), now, now));
  }

  private com.syncro.auth.application.JwtTokenService.AuthenticatedUser persistedAuthUser(com.syncro.auth.domain.ApplicationRole role, String loginIdentifier) {
    var now = java.time.Instant.parse("2026-05-27T00:00:00Z");
    var user = users.saveAndFlush(new com.syncro.auth.infrastructure.AuthUserEntity(UUID.randomUUID(), loginIdentifier,
        passwordEncoder.encode("syncro-test-password"), role, true, now, now));
    return new com.syncro.auth.application.JwtTokenService.AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private void assignPlant(com.syncro.auth.application.JwtTokenService.AuthenticatedUser user, com.syncro.auth.infrastructure.PlantEntity plant) {
    assignments.saveAndFlush(new com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plant.getId(), java.time.Instant.parse("2026-05-27T00:00:00Z")));
  }

  private static com.syncro.auth.application.JwtTokenService.AuthenticatedUser authenticatedUser(com.syncro.auth.domain.ApplicationRole role) {
    return new com.syncro.auth.application.JwtTokenService.AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }
}
