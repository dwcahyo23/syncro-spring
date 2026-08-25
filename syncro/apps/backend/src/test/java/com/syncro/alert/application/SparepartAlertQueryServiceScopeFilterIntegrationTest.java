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
 * FR-103/FR-104 scope filtering on the alert list: a section leader sees only their
 * own machine group(s) — a sibling group in the SAME section is excluded; non-leaders
 * keep the Phase 1 plant-scope behavior; SUPER_ADMIN stays unscoped.
 */
class SparepartAlertQueryServiceScopeFilterIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private SparepartAlertQueryService queryService;

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
  @DisplayName("9.1-SCOPE-FILTER-001 P1 a LEADER on a group sees only that group's alerts")
  void leaderSeesOnlyOwnGroupAlerts() {
    var leader = persistedUser("scope-filter-leader@syncro.dev");
    assign(leader, chain.plantId());
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        chain.machine1Id(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER, TS.toInstant(), TS.toInstant()));

    AlertListView result = queryService.list(leader, null, chain.plantId(), null, 0, 50, "createdAt,desc");

    assertThat(result.items()).extracting(a -> a.id()).containsExactly(chain.alert1Id());
  }

  @Test
  @DisplayName("9.1-SCOPE-FILTER-002 P1 a sibling group in the same section is excluded for the leader")
  void siblingGroupInSameSectionExcluded() {
    var leader = persistedUser("scope-filter-sibling@syncro.dev");
    assign(leader, chain.plantId());
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        chain.machine1Id(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER, TS.toInstant(), TS.toInstant()));

    // machine2 belongs to group2 in the SAME section as group1 — still excluded.
    AlertListView result = queryService.list(leader, null, chain.plantId(), null, 0, 50, "createdAt,desc");

    assertThat(result.items()).extracting(a -> a.id()).doesNotContain(chain.alert2Id());
  }

  @Test
  @DisplayName("9.1-SCOPE-FILTER-003 P1 a non-leader keeps the plant-scope behavior")
  void nonLeaderSeesAllAlertsInPlant() {
    var viewer = persistedUser("scope-filter-viewer@syncro.dev");
    assign(viewer, chain.plantId());

    AlertListView result = queryService.list(viewer, null, chain.plantId(), null, 0, 50, "createdAt,desc");

    assertThat(result.items()).extracting(a -> a.id())
        .containsExactlyInAnyOrder(chain.alert1Id(), chain.alert2Id());
  }

  @Test
  @DisplayName("9.1-SCOPE-FILTER-004 P1 SUPER_ADMIN stays unscoped")
  void superAdminSeesAllAlerts() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    AlertListView result = queryService.list(admin, null, null, null, 0, 50, "createdAt,desc");

    assertThat(result.items()).extracting(a -> a.id())
        .containsExactlyInAnyOrder(chain.alert1Id(), chain.alert2Id());
  }

  @Test
  @DisplayName("9.1-SCOPE-FILTER-005 P0 demoting the leader immediately removes the group filter")
  void demotingLeaderRemovesGroupFilter() {
    var leader = persistedUser("scope-filter-demote@syncro.dev");
    assign(leader, chain.plantId());
    var responsibility = responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        chain.machine1Id(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER, TS.toInstant(), TS.toInstant()));

    assertThat(queryService.list(leader, null, chain.plantId(), null, 0, 50, "createdAt,desc").items())
        .extracting(a -> a.id()).containsExactly(chain.alert1Id());

    responsibilities.deleteById(responsibility.getId());
    responsibilities.flush();

    assertThat(queryService.list(leader, null, chain.plantId(), null, 0, 50, "createdAt,desc").items())
        .extracting(a -> a.id()).containsExactlyInAnyOrder(chain.alert1Id(), chain.alert2Id());
  }

  @Test
  @DisplayName("9.1-SCOPE-FILTER-006 P0 a leader cannot read a sibling-group alert by id")
  void leaderCannotReadSiblingGroupAlert() {
    var leader = persistedUser("scope-filter-detail@syncro.dev");
    assign(leader, chain.plantId());
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        chain.machine1Id(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER, TS.toInstant(), TS.toInstant()));

    assertThatThrownBy(() -> queryService.get(leader, chain.alert2Id()))
        .isInstanceOf(SparepartAlertQueryService.AlertNotFoundException.class);
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

  private Chain seedChain() {
    int seq = seedSeq.incrementAndGet();
    String tag = "SF-" + seq;
    UUID plantId = UUID.randomUUID();
    UUID sectionId = UUID.randomUUID();
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
        plantId, tag, "Plant " + tag, TS, TS);
    jdbc.update("INSERT INTO sections (id, plant_id, code, name, active, version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        sectionId, plantId, "MACHINERY", "Machinery", true, 0, TS, TS);
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, section_id, created_at, updated_at) VALUES (?,?,?,?,?,?)",
        group1, plantId, "Forming " + seq, sectionId, TS, TS);
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, section_id, created_at, updated_at) VALUES (?,?,?,?,?,?)",
        group2, plantId, "Rolling " + seq, sectionId, TS, TS);
    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        machine1, plantId, group1, "M1-" + tag, "Machine 1", "ACTIVE", TS, TS);
    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        machine2, plantId, group2, "M2-" + tag, "Machine 2", "ACTIVE", TS, TS);

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

    return new Chain(plantId, sectionId, group1, group2, machine1, machine2, alert1, alert2);
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

  private record Chain(UUID plantId, UUID sectionId, UUID group1Id, UUID group2Id,
      UUID machine1Id, UUID machine2Id, UUID alert1Id, UUID alert2Id) {
  }
}