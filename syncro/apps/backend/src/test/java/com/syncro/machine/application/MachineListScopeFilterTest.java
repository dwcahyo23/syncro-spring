package com.syncro.machine.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * FR-103/FR-104 machine-hub scope filtering: a section leader sees only machines
 * in their own machine group(s); a non-leader sees all machines in the plant;
 * SUPER_ADMIN stays unscoped.
 */
class MachineListScopeFilterTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private MachineService machineService;

  @Autowired
  private MachineRepository machines;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private MachineResponsibilityRepository responsibilities;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Test
  @DisplayName("9.1-SCOPE-MACHINE-001 P1 a LEADER sees only their own group's machines")
  void leaderSeesOnlyOwnGroupMachines() {
    var setup = seedTwoGroupsTwoMachines();
    var leader = persistedUser("machine-scope-leader@syncro.dev");
    assign(leader, setup.plant());
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        setup.machine1().getId(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER,
        Instant.parse("2026-05-27T00:00:00Z"), Instant.parse("2026-05-27T00:00:00Z")));

    var result = machineService.list(leader, null, null, null);

    assertThat(result.items()).extracting(m -> m.id()).containsExactly(setup.machine1().getId());
  }

  @Test
  @DisplayName("9.1-SCOPE-MACHINE-002 P1 a sibling group's machines are excluded for the leader")
  void siblingGroupMachineExcluded() {
    var setup = seedTwoGroupsTwoMachines();
    var leader = persistedUser("machine-scope-sibling@syncro.dev");
    assign(leader, setup.plant());
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        setup.machine1().getId(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER,
        Instant.parse("2026-05-27T00:00:00Z"), Instant.parse("2026-05-27T00:00:00Z")));

    var result = machineService.list(leader, null, null, null);

    assertThat(result.items()).extracting(m -> m.id()).doesNotContain(setup.machine2().getId());
  }

  @Test
  @DisplayName("9.1-SCOPE-MACHINE-003 P1 a non-leader sees all machines in the plant")
  void nonLeaderSeesAllMachines() {
    var setup = seedTwoGroupsTwoMachines();
    var viewer = persistedUser("machine-scope-viewer@syncro.dev");
    assign(viewer, setup.plant());

    var result = machineService.list(viewer, null, null, null);

    assertThat(result.items()).extracting(m -> m.id())
        .containsExactlyInAnyOrder(setup.machine1().getId(), setup.machine2().getId());
  }

  @Test
  @DisplayName("9.1-SCOPE-MACHINE-004 P1 SUPER_ADMIN is unscoped")
  void superAdminUnscoped() {
    var setup = seedTwoGroupsTwoMachines();
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var result = machineService.list(admin, null, null, null);

    assertThat(result.items()).extracting(m -> m.id())
        .containsExactlyInAnyOrder(setup.machine1().getId(), setup.machine2().getId());
  }

  @Test
  @DisplayName("9.1-SCOPE-MACHINE-005 P0 demoting removes the group filter immediately")
  void demotingRemovesGroupFilter() {
    var setup = seedTwoGroupsTwoMachines();
    var leader = persistedUser("machine-scope-demote@syncro.dev");
    assign(leader, setup.plant());
    var responsibility = responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        setup.machine1().getId(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER,
        Instant.parse("2026-05-27T00:00:00Z"), Instant.parse("2026-05-27T00:00:00Z")));

    assertThat(machineService.list(leader, null, null, null).items()).extracting(m -> m.id())
        .containsExactly(setup.machine1().getId());

    responsibilities.deleteById(responsibility.getId());
    responsibilities.flush();

    assertThat(machineService.list(leader, null, null, null).items()).extracting(m -> m.id())
        .containsExactlyInAnyOrder(setup.machine1().getId(), setup.machine2().getId());
  }

  private ScopeSetup seedTwoGroupsTwoMachines() {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "GM1", "Plant GM1", now, now));
    var group1 = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, "Forming", now, now));
    var group2 = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, "Rolling", now, now));
    var machine1 = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group1, "BF-08410", "JBF19",
        MachineStatus.ACTIVE, "Juki", null, null, List.of(), now, now));
    var machine2 = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group2, "BF-08411", "JBF20",
        MachineStatus.ACTIVE, "Juki", null, null, List.of(), now, now));
    return new ScopeSetup(plant, group1, group2, machine1, machine2);
  }

  private AuthenticatedUser persistedUser(String loginIdentifier) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(),
        loginIdentifier,
        passwordEncoder.encode("syncro-test-password"),
        ApplicationRole.AUDITOR,
        true,
        now,
        now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), ApplicationRole.AUDITOR);
  }

  private void assign(AuthenticatedUser user, PlantEntity plant) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plant.getId(),
        Instant.parse("2026-05-27T00:00:00Z")));
  }

  private record ScopeSetup(PlantEntity plant, MachineGroupEntity group1, MachineGroupEntity group2,
      MachineEntity machine1, MachineEntity machine2) {
  }
}