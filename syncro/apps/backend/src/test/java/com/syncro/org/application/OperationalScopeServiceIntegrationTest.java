package com.syncro.org.application;

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

class OperationalScopeServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private OperationalScopeService scopeService;

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
  @DisplayName("9.1-SCOPE-001 P1 a LEADER derives exactly their own machine group")
  void leaderDerivesOwnMachineGroup() {
    var setup = seedTwoGroupsTwoMachines();
    var user = persistedUser("leader-scope@syncro.dev");
    assign(user, setup.plant());
    responsibility(user, setup.machine1(), ResponsibilityLevel.LEADER);

    var scope = scopeService.derive(user);

    assertThat(scope.machineGroupIds()).containsExactly(setup.group1().getId());
    assertThat(scope.activeTeamIds()).isEmpty();
    assertThat(scope.plantIds()).containsExactly(setup.plant().getId());
  }

  @Test
  @DisplayName("9.1-SCOPE-002 P1 SPV and MANAGER are treated as leaders")
  void spvAndManagerIncluded() {
    var setup = seedTwoGroupsTwoMachines();
    var spv = persistedUser("spv-scope@syncro.dev");
    assign(spv, setup.plant());
    responsibility(spv, setup.machine2(), ResponsibilityLevel.SPV);
    var manager = persistedUser("manager-scope@syncro.dev");
    assign(manager, setup.plant());
    responsibility(manager, setup.machine2(), ResponsibilityLevel.MANAGER);

    assertThat(scopeService.derive(spv).machineGroupIds()).containsExactly(setup.group2().getId());
    assertThat(scopeService.derive(manager).machineGroupIds()).containsExactly(setup.group2().getId());
  }

  @Test
  @DisplayName("9.1-SCOPE-003 P1 TECHNICIAN and STAFF are excluded")
  void technicianAndStaffExcluded() {
    var setup = seedTwoGroupsTwoMachines();
    var tech = persistedUser("technician-scope@syncro.dev");
    assign(tech, setup.plant());
    responsibility(tech, setup.machine1(), ResponsibilityLevel.TECHNICIAN);
    var staff = persistedUser("staff-scope@syncro.dev");
    assign(staff, setup.plant());
    responsibility(staff, setup.machine1(), ResponsibilityLevel.STAFF);

    assertThat(scopeService.derive(tech).machineGroupIds()).isEmpty();
    assertThat(scopeService.derive(staff).machineGroupIds()).isEmpty();
  }

  @Test
  @DisplayName("9.1-SCOPE-004 P0 removing the responsibility immediately empties the scope")
  void removingResponsibilityImmediatelyRemovesScope() {
    var setup = seedTwoGroupsTwoMachines();
    var user = persistedUser("demote-scope@syncro.dev");
    assign(user, setup.plant());
    var responsibility = responsibility(user, setup.machine1(), ResponsibilityLevel.LEADER);

    assertThat(scopeService.derive(user).machineGroupIds()).containsExactly(setup.group1().getId());

    responsibilities.deleteById(responsibility.getId());
    responsibilities.flush();

    assertThat(scopeService.derive(user).machineGroupIds()).isEmpty();
  }

  @Test
  @DisplayName("9.1-SCOPE-005 P1 SUPER_ADMIN keeps the unrestricted contract (plantIds null)")
  void superAdminUnrestrictedContract() {
    var setup = seedTwoGroupsTwoMachines();
    var admin = persistedUser("admin-scope@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var scope = scopeService.derive(admin);

    assertThat(scope.plantIds()).isNull();
    assertThat(scope.machineGroupIds()).isEmpty();
    // Setup proves the derivation ran against real data while returning no restriction.
    assertThat(setup.machine1().getId()).isNotNull();
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

  private MachineResponsibilityEntity responsibility(AuthenticatedUser user, MachineEntity machine,
      ResponsibilityLevel level) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    return responsibilities.saveAndFlush(new MachineResponsibilityEntity(
        UUID.randomUUID(), machine.getId(), UUID.fromString(user.id()), level, now, now));
  }

  private AuthenticatedUser persistedUser(String loginIdentifier) {
    return persistedUser(loginIdentifier, ApplicationRole.VIEWER);
  }

  private AuthenticatedUser persistedUser(String loginIdentifier, ApplicationRole role) {
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
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plant.getId(),
        Instant.parse("2026-05-27T00:00:00Z")));
  }

  private record ScopeSetup(PlantEntity plant, MachineGroupEntity group1, MachineGroupEntity group2,
      MachineEntity machine1, MachineEntity machine2) {
  }
}
