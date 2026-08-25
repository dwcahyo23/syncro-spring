package com.syncro.masterdata.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.masterdata.application.MachineGroupService.CreateMachineGroupCommand;
import com.syncro.masterdata.application.MachineGroupService.DuplicateMachineGroupNameException;
import com.syncro.masterdata.application.MachineGroupService.MachineGroupMutationForbiddenException;
import com.syncro.masterdata.application.MachineGroupService.MachineGroupNotFoundException;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

class MachineGroupServiceIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired
  private MachineGroupService machineGroupService;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Test
  @DisplayName("2.2-SVC-001 P1 MANAGER_MAINTENANCE creates normalized machine group under assigned plant")
  void manageCreatesMachineGroupUnderAssignedPlant() {
    var plant = plant("GM1", "Plant GM1");
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "manage-machine-group@syncro.dev");
    assign(user, plant);

    var created = machineGroupService.create(user, new CreateMachineGroupCommand(plant.getId(), " Forming "));

    assertThat(created.plantId()).isEqualTo(plant.getId());
    assertThat(created.plantCode()).isEqualTo("GM1");
    assertThat(created.name()).isEqualTo("Forming");
    assertThat(machineGroups.findById(created.id())).isPresent();
  }

  @Test
  @DisplayName("2.2-SVC-002 P1 duplicate same-plant machine group name is rejected case-insensitively")
  void duplicateSamePlantNameIsRejectedCaseInsensitively() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "Forming"));

    assertThatThrownBy(() -> machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "forming")))
        .isInstanceOf(DuplicateMachineGroupNameException.class);
  }

  @Test
  @DisplayName("2.2-SVC-003 P1 same machine group name is allowed across different plants")
  void sameNameAllowedAcrossDifferentPlants() {
    var firstPlant = plant("GM1", "Plant GM1");
    var secondPlant = plant("GM2", "Plant GM2");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var first = machineGroupService.create(admin, new CreateMachineGroupCommand(firstPlant.getId(), "Forming"));
    var second = machineGroupService.create(admin, new CreateMachineGroupCommand(secondPlant.getId(), "Forming"));

    assertThat(first.id()).isNotEqualTo(second.id());
    assertThat(machineGroups.findAll()).hasSize(2);
  }

  @Test
  @DisplayName("2.2-SVC-010 P0 database rejects case-insensitive duplicate machine group names")
  void databaseRejectsCaseInsensitiveDuplicateNames() {
    var plant = plant("GM1", "Plant GM1");
    var now = Instant.parse("2026-05-27T00:00:00Z");
    machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, "Forming", now, now));

    assertThatThrownBy(() -> machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, "forming", now, now)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("2.2-SVC-011 P1 update cannot move machine group to another plant")
  void updateCannotMoveMachineGroupToAnotherPlant() {
    var sourcePlant = plant("GM1", "Plant GM1");
    var targetPlant = plant("GM2", "Plant GM2");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var group = machineGroupService.create(admin, new CreateMachineGroupCommand(sourcePlant.getId(), "Forming"));

    assertThatThrownBy(() -> machineGroupService.update(admin, group.id(), new CreateMachineGroupCommand(targetPlant.getId(), "Forming")))
        .isInstanceOf(MachineGroupService.MachineGroupDataIntegrityException.class);

    assertThat(machineGroups.findById(group.id())).get().extracting(entity -> entity.getPlant().getId()).isEqualTo(sourcePlant.getId());
  }

  @Test
  @DisplayName("2.2-SVC-004 P1 AUDITOR lists assigned plant machine groups only")
  void viewerListsAssignedPlantMachineGroupsOnly() {
    var assigned = plant("GM1", "Plant GM1");
    var other = plant("GM2", "Plant GM2");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var viewer = persistedUser(ApplicationRole.AUDITOR, "viewer-machine-group@syncro.dev");
    assign(viewer, assigned);
    var assignedGroup = machineGroupService.create(admin, new CreateMachineGroupCommand(assigned.getId(), "Forming"));
    machineGroupService.create(admin, new CreateMachineGroupCommand(other.getId(), "Packing"));

    var result = machineGroupService.list(viewer, assigned.getId());

    assertThat(result.items()).extracting(group -> group.id()).containsExactly(assignedGroup.id());
  }

  // --- DW-120: search LIKE escape ---

  @Test
  @DisplayName("DW-120 underscore in group search matches literally instead of acting as wildcard")
  void searchUnderscoreMatchesLiterally() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "Assembly_line"));

    var literal = machineGroupService.list(admin, plant.getId(), "_line", 0, 50, "name,asc");
    assertThat(literal.items()).extracting(g -> g.name()).containsExactly("Assembly_line");

    // A % must not widen the match either.
    var percent = machineGroupService.list(admin, plant.getId(), "%line", 0, 50, "name,asc");
    assertThat(percent.items()).isEmpty();
  }

  @Test
  @DisplayName("2.2-SVC-005 P0 MANAGER_MAINTENANCE cannot list out-of-scope plant machine groups")
  void manageCannotListOutOfScopePlantMachineGroups() {
    var target = plant("GM1", "Plant GM1");
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "manage-out-of-scope-list@syncro.dev");

    assertThatThrownBy(() -> machineGroupService.list(user, target.getId()))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("2.2-SVC-006 P0 MANAGER_MAINTENANCE cannot update out-of-scope machine group")
  void manageCannotUpdateOutOfScopeMachineGroup() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var group = machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "Forming"));
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "manage-out-of-scope-update@syncro.dev");

    assertThatThrownBy(() -> machineGroupService.update(user, group.id(), new CreateMachineGroupCommand(plant.getId(), "Packing")))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("2.2-SVC-007 P0 AUDITOR cannot mutate machine groups")
  void viewerCannotMutateMachineGroups() {
    var plant = plant("GM1", "Plant GM1");
    var viewer = persistedUser(ApplicationRole.AUDITOR, "viewer-mutates-machine-group@syncro.dev");
    assign(viewer, plant);

    assertThatThrownBy(() -> machineGroupService.create(viewer, new CreateMachineGroupCommand(plant.getId(), "Forming")))
        .isInstanceOf(MachineGroupMutationForbiddenException.class);
  }

  @Test
  @DisplayName("2.2-SVC-008 P1 delete removes machine group without dependents")
  void deleteRemovesMachineGroupWithoutDependents() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var group = machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "Forming"));

    machineGroupService.delete(admin, group.id());

    assertThat(machineGroups.findById(group.id())).isEmpty();
  }

  @Test
  @DisplayName("2.2-SVC-009 P1 missing machine group is rejected safely")
  void missingMachineGroupRejectedSafely() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> machineGroupService.get(admin, UUID.randomUUID()))
        .isInstanceOf(MachineGroupNotFoundException.class);
  }

  private PlantEntity plant(String code, String name) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    return plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), code, name, now, now));
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(),
        loginIdentifier,
        passwordEncoder.encode("syncro-test-password"),
        role,
        true,
        now,
        now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private void assign(AuthenticatedUser user, PlantEntity plant) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plant.getId(), Instant.parse("2026-05-27T00:00:00Z")));
  }

  private static AuthenticatedUser authenticatedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }
}
