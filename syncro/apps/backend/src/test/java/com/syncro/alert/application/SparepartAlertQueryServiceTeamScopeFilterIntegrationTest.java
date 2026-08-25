package com.syncro.alert.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.alert.application.SparepartAlertQueryService.AlertListView;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.org.application.TeamService;
import com.syncro.org.application.TeamService.CreateTeamCommand;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Cross-plant team scope filtering on the alert list/detail (AD-13): a team member
 * gains an additive cross-plant branch — base plant view unchanged, plus any alert
 * whose machine group is in the team's target groups. Expired teams and removed
 * members contribute nothing; SUPER_ADMIN stays unscoped.
 */
class SparepartAlertQueryServiceTeamScopeFilterIntegrationTest extends AbstractPostgresIntegrationTest {

  @PersistenceContext
  private EntityManager entityManager;

  @Autowired
  private SparepartAlertQueryService queryService;

  @Autowired
  private SparepartAlertCommandService commandService;

  @Autowired
  private TeamService teamService;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private MachineResponsibilityRepository responsibilities;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-25T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  private Chain chain;

  @BeforeEach
  void seed() {
    chain = seedChain();
  }

  @Test
  @DisplayName("9.2-FILTER-001 P1 a technician on a team sees base plant alerts plus cross-plant team alerts")
  void technicianSeesBasePlusTeamAlerts() {
    var tech = persistedUser("tech-team@syncro.dev");
    assign(tech, chain.gm1PlantId());
    var team = team(tech, chain.sm2MachineId(), "Cross Alert Help");

    AlertListView result = queryService.list(tech, null, null, null, 0, 50, "createdAt,desc");

    assertThat(result.items()).extracting(a -> a.id())
        .containsExactlyInAnyOrder(chain.gm1AlertId(), chain.sm2AlertId());
  }

  @Test
  @DisplayName("9.2-FILTER-002 P1 a leader's group scope is unchanged while team scope is additive (union)")
  void leaderPlusTeamUnion() {
    var leader = persistedUser("leader-team-filter@syncro.dev");
    assign(leader, chain.gm1PlantId());
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        chain.gm1MachineId(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER, TS.toInstant(), TS.toInstant()));
    team(leader, chain.sm2MachineId(), "Leader Alert Help");

    AlertListView result = queryService.list(leader, null, null, null, 0, 50, "createdAt,desc");

    assertThat(result.items()).extracting(a -> a.id())
        .containsExactlyInAnyOrder(chain.gm1AlertId(), chain.sm2AlertId());
  }

  @Test
  @DisplayName("9.2-FILTER-003 P0 an expired team contributes no cross-plant alerts")
  void expiredTeamExcluded() {
    var tech = persistedUser("expired-filter@syncro.dev");
    assign(tech, chain.gm1PlantId());
    var team = team(tech, chain.sm2MachineId(), "Expiring Alert Help");
    assertThat(queryService.list(tech, null, null, null, 0, 50, "createdAt,desc").items())
        .extracting(a -> a.id()).contains(chain.sm2AlertId());

    jdbc.update("UPDATE teams SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)), team.id());
    entityManager.flush();

    AlertListView result = queryService.list(tech, null, null, null, 0, 50, "createdAt,desc");
    assertThat(result.items()).extracting(a -> a.id()).containsExactly(chain.gm1AlertId());
  }

  @Test
  @DisplayName("9.2-FILTER-004 P1 SUPER_ADMIN stays unscoped")
  void superAdminUnscoped() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    AlertListView result = queryService.list(admin, null, null, null, 0, 50, "createdAt,desc");

    assertThat(result.items()).extracting(a -> a.id())
        .containsExactlyInAnyOrder(chain.gm1AlertId(), chain.sm2AlertId());
  }

  @Test
  @DisplayName("9.2-FILTER-005 P0 removing the member removes cross-plant alerts immediately")
  void memberRemovalImmediatelyExcludes() {
    var tech = persistedUser("remove-filter@syncro.dev");
    assign(tech, chain.gm1PlantId());
    var team = team(tech, chain.sm2MachineId(), "Removal Alert Help");
    assertThat(queryService.list(tech, null, null, null, 0, 50, "createdAt,desc").items())
        .extracting(a -> a.id()).contains(chain.sm2AlertId());

    teamService.removeMember(superAdmin(), team.id(), UUID.fromString(tech.id()));
    entityManager.flush();

    AlertListView result = queryService.list(tech, null, null, null, 0, 50, "createdAt,desc");
    assertThat(result.items()).extracting(a -> a.id()).containsExactly(chain.gm1AlertId());
  }

  @Test
  @DisplayName("9.2-FILTER-006 P0 a non-leader without a team keeps the Phase 1 plant-scope view")
  void noTeamRegression() {
    var viewer = persistedUser("no-team-filter@syncro.dev");
    assign(viewer, chain.gm1PlantId());

    AlertListView result = queryService.list(viewer, null, null, null, 0, 50, "createdAt,desc");

    assertThat(result.items()).extracting(a -> a.id()).containsExactly(chain.gm1AlertId());
  }

  @Test
  @DisplayName("9.2-FILTER-006b P1 a member with no plant assignment still gets the team branch on alerts")
  void plantlessMemberGetsTeamBranch() {
    var member = persistedUser("plantless-team@syncro.dev");
    // No plant assignment at all — the team grant must not be dead on alert paths.
    team(member, chain.sm2MachineId(), "Plantless Alert Help");

    AlertListView result = queryService.list(member, null, null, null, 0, 50, "createdAt,desc");
    assertThat(result.items()).extracting(a -> a.id()).containsExactly(chain.sm2AlertId());

    var detail = queryService.get(member, chain.sm2AlertId());
    assertThat(detail.id()).isEqualTo(chain.sm2AlertId());
  }

  @Test
  @DisplayName("9.2-FILTER-006c P0 a team member can act on a visible cross-plant alert (no view/act asymmetry)")
  void teamMemberCanAcknowledgeCrossPlantAlert() {
    var tech = persistedUser("act-team@syncro.dev");
    assign(tech, chain.gm1PlantId());
    team(tech, chain.sm2MachineId(), "Act Alert Help");

    commandService.acknowledge(tech, chain.sm2AlertId(), "cross-plant repair");

    var detail = queryService.get(tech, chain.sm2AlertId());
    assertThat(detail.status()).isEqualTo(com.syncro.alert.domain.SparepartAlertStatus.ACKNOWLEDGED);
  }

  @Test
  @DisplayName("9.2-FILTER-007 P1 a team member can read a cross-plant alert detail via team scope")
  void teamMemberReadsCrossPlantAlertDetail() {
    var tech = persistedUser("detail-team-filter@syncro.dev");
    assign(tech, chain.gm1PlantId());
    team(tech, chain.sm2MachineId(), "Detail Alert Help");

    var detail = queryService.get(tech, chain.sm2AlertId());

    assertThat(detail.id()).isEqualTo(chain.sm2AlertId());
    assertThat(detail.plantCode()).isEqualTo("SM2");
  }

  @Test
  @DisplayName("9.2-FILTER-008 P0 a non-member cannot read a cross-plant alert detail")
  void nonMemberCannotReadCrossPlantAlert() {
    var outsider = persistedUser("outsider-filter@syncro.dev");
    assign(outsider, chain.gm1PlantId());

    assertThatThrownBy(() -> queryService.get(outsider, chain.sm2AlertId()))
        .isInstanceOf(SparepartAlertQueryService.AlertNotFoundException.class);
  }

  private com.syncro.org.application.TeamService.TeamView team(AuthenticatedUser member, UUID machineId,
      String name) {
    var created = teamService.create(superAdmin(), new CreateTeamCommand(name, Instant.now().plusSeconds(30 * 86400)));
    teamService.addMember(superAdmin(), created.id(), UUID.fromString(member.id()));
    teamService.linkMachine(superAdmin(), created.id(), machineId);
    return created;
  }

  private AuthenticatedUser persistedUser(String loginIdentifier) {
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(),
        loginIdentifier,
        passwordEncoder.encode("syncro-test-password"),
        ApplicationRole.VIEWER,
        true,
        TS.toInstant(),
        TS.toInstant()));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), ApplicationRole.VIEWER);
  }

  private void assign(AuthenticatedUser user, UUID plantId) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plantId, TS.toInstant()));
  }

  private static AuthenticatedUser superAdmin() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
  }

  private Chain seedChain() {
    int seq = seedSeq.incrementAndGet();
    String tag = "TS-" + seq;
    UUID gm1Plant = UUID.randomUUID();
    UUID sm2Plant = UUID.randomUUID();
    UUID group1 = UUID.randomUUID();
    UUID group2 = UUID.randomUUID();
    UUID machine1 = UUID.randomUUID();
    UUID machine2 = UUID.randomUUID();
    UUID catId = UUID.randomUUID();
    UUID brand1 = UUID.randomUUID();
    UUID kind1 = UUID.randomUUID();
    UUID type1 = UUID.randomUUID();
    UUID brand2 = UUID.randomUUID();
    UUID kind2 = UUID.randomUUID();
    UUID type2 = UUID.randomUUID();
    UUID sparepart1 = UUID.randomUUID();
    UUID sparepart2 = UUID.randomUUID();
    UUID installation1 = UUID.randomUUID();
    UUID installation2 = UUID.randomUUID();
    UUID alert1 = UUID.randomUUID();
    UUID alert2 = UUID.randomUUID();

    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        gm1Plant, "GM1", "Plant GM1", TS, TS);
    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        sm2Plant, "SM2", "Sinar Mas 2", TS, TS);
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        group1, gm1Plant, "Forming " + seq, TS, TS);
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        group2, sm2Plant, "Packaging " + seq, TS, TS);
    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        machine1, gm1Plant, group1, "M1-" + tag, "Machine 1", "ACTIVE", TS, TS);
    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        machine2, sm2Plant, group2, "M2-" + tag, "Machine 2", "ACTIVE", TS, TS);

    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, created_at, updated_at) VALUES (?,?,?,?,?,?)",
        catId, "CATEGORY", "CAT-" + tag, "Cat " + seq, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        brand1, "BRAND", "BRAND1-" + tag, "Brand 1", catId, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        kind1, "KIND", "KIND1-" + tag, "Kind 1", catId, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        type1, "TYPE", "TYPE1-" + tag, "Type 1", catId, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        brand2, "BRAND", "BRAND2-" + tag, "Brand 2", catId, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        kind2, "KIND", "KIND2-" + tag, "Kind 2", catId, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        type2, "TYPE", "TYPE2-" + tag, "Type 2", catId, TS, TS);

    jdbc.update("INSERT INTO spareparts (id, code, name, machine_id, category_id, brand_id, kind_id, type_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        sparepart1, "SP1-" + tag, "Sparepart 1", machine1, catId, brand1, kind1, type1, TS, TS);
    jdbc.update("INSERT INTO spareparts (id, code, name, machine_id, category_id, brand_id, kind_id, type_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        sparepart2, "SP2-" + tag, "Sparepart 2", machine2, catId, brand2, kind2, type2, TS, TS);
    jdbc.update("INSERT INTO machine_sparepart_installations (id, machine_id, sparepart_id, function_name, expected_production_count, baseline_counter, threshold_percentage, installed_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        installation1, machine1, sparepart1, "func1", 1000, 0, 80, TS, TS, TS);
    jdbc.update("INSERT INTO machine_sparepart_installations (id, machine_id, sparepart_id, function_name, expected_production_count, baseline_counter, threshold_percentage, installed_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        installation2, machine2, sparepart2, "func2", 1000, 0, 80, TS, TS, TS);

    insertAlert(alert1, machine1, installation1, "tr-" + tag + "-1");
    insertAlert(alert2, machine2, installation2, "tr-" + tag + "-2");

    return new Chain(gm1Plant, sm2Plant, group1, group2, machine1, machine2, alert1, alert2);
  }

  private void insertAlert(UUID alertId, UUID machineId, UUID installationId, String trace) {
    jdbc.update("""
        INSERT INTO sparepart_alerts
          (id, machine_id, machine_sparepart_installation_id, alert_type, threshold_percentage,
           current_counter_snapshot, consumed_production_count_snapshot, consumed_percentage_snapshot,
           trace_id, status, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """, alertId, machineId, installationId, "THRESHOLD_PERCENTAGE", 80,
        100L, 90L, new BigDecimal("90.00"), trace, "OPEN", TS, TS);
  }

  private record Chain(UUID gm1PlantId, UUID sm2PlantId, UUID group1Id, UUID group2Id,
      UUID gm1MachineId, UUID sm2MachineId, UUID gm1AlertId, UUID sm2AlertId) {
  }
}
