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
import com.syncro.org.application.TeamService.CreateTeamCommand;
import com.syncro.org.infrastructure.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Cross-plant team scope derivation (AD-13): active-team target machines resolve to
 * machine-group ids in {@code activeTeamIds}; expired teams and removed members are
 * excluded lazily per derive; leader scope and team scope are additive.
 */
class OperationalScopeTeamScopeIntegrationTest extends AbstractPostgresIntegrationTest {

  @PersistenceContext
  private EntityManager entityManager;

  @Autowired
  private OperationalScopeService scopeService;

  @Autowired
  private TeamService teamService;

  @Autowired
  private TeamRepository teams;

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

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  @DisplayName("9.2-SCOPE-001 P1 an active team adds its target machine-group to activeTeamIds")
  void activeTeamAddsCrossPlantGroup() {
    var setup = seedCrossPlant();
    var user = persistedUser("technician-team@syncro.dev");
    assign(user, setup.gm1());
    var team = teamService.create(superAdmin(), new CreateTeamCommand("Forming Help", future(30)));
    teamService.addMember(superAdmin(), team.id(), UUID.fromString(user.id()));
    teamService.linkMachine(superAdmin(), team.id(), setup.packagingMachine().getId());

    var scope = scopeService.derive(user);

    assertThat(scope.activeTeamIds()).containsExactly(setup.packagingGroup().getId());
    assertThat(scope.machineGroupIds()).isEmpty();
    assertThat(scope.plantIds()).containsExactly(setup.gm1().getId());
  }

  @Test
  @DisplayName("9.2-SCOPE-002 P0 an expired team contributes no scope")
  void expiredTeamExcluded() {
    var setup = seedCrossPlant();
    var user = persistedUser("expired-team@syncro.dev");
    assign(user, setup.gm1());
    var team = teamService.create(superAdmin(), new CreateTeamCommand("Expiring Help", future(30)));
    teamService.addMember(superAdmin(), team.id(), UUID.fromString(user.id()));
    teamService.linkMachine(superAdmin(), team.id(), setup.packagingMachine().getId());
    assertThat(scopeService.derive(user).activeTeamIds()).containsExactly(setup.packagingGroup().getId());

    jdbc.update("UPDATE teams SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)), team.id());
    entityManager.flush();

    assertThat(scopeService.derive(user).activeTeamIds()).isEmpty();
  }

  @Test
  @DisplayName("9.2-SCOPE-003 P0 removing the member removes the scope immediately")
  void memberRemovalImmediatelyRemovesScope() {
    var setup = seedCrossPlant();
    var user = persistedUser("removed-member@syncro.dev");
    assign(user, setup.gm1());
    var team = teamService.create(superAdmin(), new CreateTeamCommand("Removal Help", future(30)));
    teamService.addMember(superAdmin(), team.id(), UUID.fromString(user.id()));
    teamService.linkMachine(superAdmin(), team.id(), setup.packagingMachine().getId());
    assertThat(scopeService.derive(user).activeTeamIds()).containsExactly(setup.packagingGroup().getId());

    teamService.removeMember(superAdmin(), team.id(), UUID.fromString(user.id()));
    entityManager.flush();

    assertThat(scopeService.derive(user).activeTeamIds()).isEmpty();
  }

  @Test
  @DisplayName("9.2-SCOPE-004 P1 leader scope and team scope are additive (union)")
  void leaderPlusTeamUnion() {
    var setup = seedCrossPlant();
    var user = persistedUser("leader-team@syncro.dev");
    assign(user, setup.gm1());
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        setup.gm1Machine().getId(), UUID.fromString(user.id()), ResponsibilityLevel.LEADER,
        Instant.parse("2026-05-27T00:00:00Z"), Instant.parse("2026-05-27T00:00:00Z")));
    var team = teamService.create(superAdmin(), new CreateTeamCommand("Leader Help", future(30)));
    teamService.addMember(superAdmin(), team.id(), UUID.fromString(user.id()));
    teamService.linkMachine(superAdmin(), team.id(), setup.packagingMachine().getId());

    var scope = scopeService.derive(user);

    assertThat(scope.machineGroupIds()).containsExactly(setup.formingGroup().getId());
    assertThat(scope.activeTeamIds()).containsExactly(setup.packagingGroup().getId());
  }

  @Test
  @DisplayName("9.2-SCOPE-005 P0 a user with no team keeps activeTeamIds empty (9-1 regression)")
  void noTeamRegression() {
    var setup = seedCrossPlant();
    var user = persistedUser("no-team@syncro.dev");
    assign(user, setup.gm1());

    var scope = scopeService.derive(user);

    assertThat(scope.activeTeamIds()).isEmpty();
    assertThat(scope.machineGroupIds()).isEmpty();
    assertThat(scope.plantIds()).containsExactly(setup.gm1().getId());
  }

  private ScopeSetup seedCrossPlant() {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var gm1 = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "GM1", "Plant GM1", now, now));
    var sm2 = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "SM2", "Sinar Mas 2", now, now));
    var forming = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), gm1, "Forming", now, now));
    var packaging = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), sm2, "Packaging", now, now));
    var gm1Machine = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), gm1, forming, "BF-08410", "JBF19",
        MachineStatus.ACTIVE, "Juki", null, null, List.of(), now, now));
    var packagingMachine = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), sm2, packaging, "PK-0001",
        "PackLine", MachineStatus.ACTIVE, "Juki", null, null, List.of(), now, now));
    return new ScopeSetup(gm1, sm2, forming, packaging, gm1Machine, packagingMachine);
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

  private static AuthenticatedUser superAdmin() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
  }

  private static Instant future(long days) {
    return Instant.now().plusSeconds(days * 24 * 3600);
  }

  private record ScopeSetup(PlantEntity gm1, PlantEntity sm2, MachineGroupEntity formingGroup,
      MachineGroupEntity packagingGroup, MachineEntity gm1Machine, MachineEntity packagingMachine) {
  }
}
