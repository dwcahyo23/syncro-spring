package com.syncro.machine.application;

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
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.org.application.TeamService;
import com.syncro.org.application.TeamService.CreateTeamCommand;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Cross-plant team scope on the machine list and detail (AD-13): a team member sees
 * and can open target machines from another plant (additive plant-gate relaxation),
 * while a non-member keeps the Phase 1 plant-only gate.
 */
class MachineListTeamScopeFilterTest extends AbstractPostgresIntegrationTest {

  @PersistenceContext
  private EntityManager entityManager;

  @Autowired
  private MachineService machineService;

  @Autowired
  private MachineRepository machines;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private MachineResponsibilityRepository responsibilities;

  @Autowired
  private TeamService teamService;

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

  private Chain chain;

  @BeforeEach
  void seed() {
    chain = seedChain();
  }

  @Test
  @DisplayName("9.2-MACHINE-001 P1 a team member sees the cross-plant target machine in the list")
  void teamMemberSeesCrossPlantMachine() {
    var tech = persistedUser("machine-tech-team@syncro.dev");
    assign(tech, chain.gm1Plant());
    team(tech, chain.sm2Machine().getId(), "Machine Cross Help");

    var result = machineService.list(tech, null, null, null);

    assertThat(result.items()).extracting(m -> m.id())
        .containsExactlyInAnyOrder(chain.gm1Machine().getId(), chain.sm2Machine().getId());
  }

  @Test
  @DisplayName("9.2-MACHINE-002 P1 a team member can open the cross-plant machine detail")
  void teamMemberCanOpenCrossPlantMachineDetail() {
    var tech = persistedUser("machine-detail-team@syncro.dev");
    assign(tech, chain.gm1Plant());
    team(tech, chain.sm2Machine().getId(), "Machine Detail Help");

    var detail = machineService.get(tech, chain.sm2Machine().getId());

    assertThat(detail.id()).isEqualTo(chain.sm2Machine().getId());
    assertThat(detail.plantCode()).isEqualTo("SM2");
  }

  @Test
  @DisplayName("9.2-MACHINE-003 P0 a non-member cannot open an out-of-plant machine detail")
  void nonMemberCannotOpenOutOfPlantMachine() {
    var outsider = persistedUser("machine-outsider@syncro.dev");
    assign(outsider, chain.gm1Plant());

    assertThatThrownBy(() -> machineService.get(outsider, chain.sm2Machine().getId()))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("9.2-MACHINE-004 P1 a leader's group scope is additive with team scope in the list")
  void leaderPlusTeamScopeInList() {
    var leader = persistedUser("machine-leader-team@syncro.dev");
    assign(leader, chain.gm1Plant());
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        chain.gm1Machine().getId(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER,
        Instant.parse("2026-05-27T00:00:00Z"), Instant.parse("2026-05-27T00:00:00Z")));
    team(leader, chain.sm2Machine().getId(), "Machine Leader Help");

    var result = machineService.list(leader, null, null, null);

    assertThat(result.items()).extracting(m -> m.id())
        .containsExactlyInAnyOrder(chain.gm1Machine().getId(), chain.sm2Machine().getId());
  }

  @Test
  @DisplayName("9.2-MACHINE-005 P0 an expired team contributes no cross-plant machine")
  void expiredTeamExcludedFromList() {
    var tech = persistedUser("machine-expired@syncro.dev");
    assign(tech, chain.gm1Plant());
    var team = team(tech, chain.sm2Machine().getId(), "Machine Expired Help");
    assertThat(machineService.list(tech, null, null, null).items())
        .extracting(m -> m.id()).contains(chain.sm2Machine().getId());

    jdbc.update("UPDATE teams SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)), team.id());
    entityManager.flush();

    var result = machineService.list(tech, null, null, null);
    assertThat(result.items()).extracting(m -> m.id()).containsExactly(chain.gm1Machine().getId());
  }

  @Test
  @DisplayName("9.2-MACHINE-005b P0 an expired team no longer grants the cross-plant machine detail")
  void expiredTeamDeniesMachineDetail() {
    var tech = persistedUser("machine-expired-detail@syncro.dev");
    assign(tech, chain.gm1Plant());
    var team = team(tech, chain.sm2Machine().getId(), "Machine Expired Detail Help");
    assertThat(machineService.get(tech, chain.sm2Machine().getId()).id())
        .isEqualTo(chain.sm2Machine().getId());

    jdbc.update("UPDATE teams SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)), team.id());
    entityManager.flush();

    assertThatThrownBy(() -> machineService.get(tech, chain.sm2Machine().getId()))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("9.2-MACHINE-006 P1 SUPER_ADMIN stays unscoped")
  void superAdminUnscoped() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var result = machineService.list(admin, null, null, null);

    assertThat(result.items()).extracting(m -> m.id())
        .containsExactlyInAnyOrder(chain.gm1Machine().getId(), chain.sm2Machine().getId());
  }

  @Test
  @DisplayName("9.2-MACHINE-007 P0 removing the member removes the cross-plant machine immediately")
  void memberRemovalRemovesCrossPlantMachine() {
    var tech = persistedUser("machine-remove@syncro.dev");
    assign(tech, chain.gm1Plant());
    var team = team(tech, chain.sm2Machine().getId(), "Machine Removal Help");
    assertThat(machineService.list(tech, null, null, null).items())
        .extracting(m -> m.id()).contains(chain.sm2Machine().getId());

    teamService.removeMember(superAdmin(), team.id(), UUID.fromString(tech.id()));
    entityManager.flush();

    var result = machineService.list(tech, null, null, null);
    assertThat(result.items()).extracting(m -> m.id()).containsExactly(chain.gm1Machine().getId());
  }

  private com.syncro.org.application.TeamService.TeamView team(AuthenticatedUser member, UUID machineId,
      String name) {
    var created = teamService.create(superAdmin(), new CreateTeamCommand(name, Instant.now().plusSeconds(30 * 86400)));
    teamService.addMember(superAdmin(), created.id(), UUID.fromString(member.id()));
    teamService.linkMachine(superAdmin(), created.id(), machineId);
    return created;
  }

  private AuthenticatedUser persistedUser(String loginIdentifier) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(),
        loginIdentifier,
        passwordEncoder.encode("syncro-test-password"),
        ApplicationRole.VIEWER,
        true,
        now,
        now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), ApplicationRole.VIEWER);
  }

  private void assign(AuthenticatedUser user, PlantEntity plant) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plant.getId(),
        Instant.parse("2026-05-27T00:00:00Z")));
  }

  private static AuthenticatedUser superAdmin() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
  }

  private Chain seedChain() {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var gm1 = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "GM1", "Plant GM1", now, now));
    var sm2 = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "SM2", "Sinar Mas 2", now, now));
    var forming = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), gm1, "Forming", now, now));
    var packaging = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), sm2, "Packaging", now, now));
    var gm1Machine = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), gm1, forming, "BF-08410", "JBF19",
        MachineStatus.ACTIVE, "Juki", null, null, List.of(), now, now));
    var sm2Machine = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), sm2, packaging, "PK-0001",
        "PackLine", MachineStatus.ACTIVE, "Juki", null, null, List.of(), now, now));
    return new Chain(gm1, sm2, forming, packaging, gm1Machine, sm2Machine);
  }

  private record Chain(PlantEntity gm1Plant, PlantEntity sm2Plant, MachineGroupEntity formingGroup,
      MachineGroupEntity packagingGroup, MachineEntity gm1Machine, MachineEntity sm2Machine) {
  }
}
