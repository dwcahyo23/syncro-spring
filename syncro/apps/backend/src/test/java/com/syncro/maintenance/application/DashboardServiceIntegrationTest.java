package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.alert.domain.SparepartAlertStatus;
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
import com.syncro.maintenance.api.DashboardDtos.MachineDashboardResponse;
import com.syncro.maintenance.api.DashboardDtos.PreventiveDashboardResponse;
import com.syncro.maintenance.api.DashboardDtos.WorkorderDashboardResponse;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.org.application.TeamService;
import com.syncro.org.application.TeamService.CreateTeamCommand;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
 * Dashboard service integration tests (story 14-1): scope filtering, empty states,
 * due/overdue derivation, plantId out-of-scope, no-telemetry stale handling,
 * open-WO excludes terminal, open-alert excludes RESOLVED, byCategory incl.
 * uncategorized, team-scope, FR-171 filters, AT_RISK lifetime (requires Redis),
 * 403-branch, and mislabeled empty-state test fix.
 *
 * <p>AT_RISK lifetime evaluation requires Redis (unavailable in the Postgres-only
 * test container); the {@link SparepartLifetimeEvaluator} unit tests cover the
 * evaluation logic directly. The dashboard integration test verifies the NO_DATA
 * path (no installations → no data).
 */
class DashboardServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  @PersistenceContext
  private EntityManager entityManager;

  @Autowired
  private DashboardService dashboardService;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private MachineResponsibilityRepository responsibilities;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private MachineRepository machines;

  @Autowired
  private TeamService teamService;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-25T09:00:00Z"));
  private static final AtomicInteger seq = new AtomicInteger();

  private Chain chain;

  @BeforeEach
  void seed() {
    chain = seedChain();
  }

  // ---------------------------------------------------------------------------
  // Machine dashboard
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("14.1-DASHBOARD-001 P1 machine dashboard returns scope-filtered rows")
  void machineDashboardScopeFiltered() {
    var leader = persistedUser("dash-mc-leader@syncro.dev");
    assign(leader, chain.plant1Id());
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        chain.machine1Id(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER,
        TS.toInstant(), TS.toInstant()));

    var result = dashboardService.machineDashboard(leader, null);

    assertThat(result.items()).extracting(m -> m.machineId()).containsExactly(chain.machine1Id());
  }

  @Test
  @DisplayName("14.1-DASHBOARD-002 P1 SUPER_ADMIN sees all machines")
  void superAdminSeesAllMachines() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var result = dashboardService.machineDashboard(admin, null);

    assertThat(result.items()).extracting(m -> m.machineId())
        .containsExactlyInAnyOrder(chain.machine1Id(), chain.machine2Id());
  }

  @Test
  @DisplayName("14.1-DASHBOARD-003 P1 plantId outside scope returns empty")
  void plantIdOutsideScopeReturnsEmpty() {
    var leader = persistedUser("dash-mc-outside@syncro.dev");
    assign(leader, chain.plant1Id());
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        chain.machine1Id(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER,
        TS.toInstant(), TS.toInstant()));

    var result = dashboardService.machineDashboard(leader, chain.plant2Id());

    assertThat(result.items()).isEmpty();
  }

  @Test
  @DisplayName("14.1-DASHBOARD-004 P1 no-telemetry machine renders telemetry as null (unknown)")
  void noTelemetryMachineRendersNull() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var result = dashboardService.machineDashboard(admin, null);

    assertThat(result.items()).isNotEmpty();
    for (var row : result.items()) {
      // No Redis telemetry seeded → telemetry is null.
      assertThat(row.telemetryFreshness()).isNull();
    }
  }

  @Test
  @DisplayName("14.1-DASHBOARD-012 P1 open-WO count excludes terminal statuses")
  void openWorkOrderCountExcludesTerminal() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
    // Seed a DONE workorder on machine1 — should not be counted.
    seedWorkOrder(chain.machine1Id(), "PENDING_REVIEW");

    var result = dashboardService.machineDashboard(admin, null);

    var machine1Row = result.items().stream()
        .filter(r -> r.machineId().equals(chain.machine1Id()))
        .findFirst();
    assertThat(machine1Row).isPresent();
    // Only the 2 OPEN workorders count, not the DONE one.
    assertThat(machine1Row.get().openWorkOrderCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("14.1-DASHBOARD-013 P1 open-alert count excludes RESOLVED alerts")
  void openAlertCountExcludesResolved() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
    // Seed a RESOLVED alert on machine1 — should not be counted.
    jdbc.update("""
        INSERT INTO sparepart_alerts (id, machine_id, machine_sparepart_installation_id, alert_type,
          threshold_percentage, current_counter_snapshot, consumed_production_count_snapshot,
          consumed_percentage_snapshot, trace_id, status, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), chain.machine1Id(), chain.installation1Id(), "THRESHOLD_PERCENTAGE",
        80, 100L, 90L, new BigDecimal("90.00"), "tr-" + seq.incrementAndGet(), "RESOLVED", TS, TS);

    // Seed an OPEN alert on machine1 — should be counted.
    jdbc.update("""
        INSERT INTO sparepart_alerts (id, machine_id, machine_sparepart_installation_id, alert_type,
          threshold_percentage, current_counter_snapshot, consumed_production_count_snapshot,
          consumed_percentage_snapshot, trace_id, status, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """, UUID.randomUUID(), chain.machine1Id(), chain.installation1Id(), "THRESHOLD_PERCENTAGE",
        80, 100L, 90L, new BigDecimal("90.00"), "tr-" + seq.incrementAndGet(), "OPEN", TS, TS);

    var result = dashboardService.machineDashboard(admin, null);

    var machine1Row = result.items().stream()
        .filter(r -> r.machineId().equals(chain.machine1Id()))
        .findFirst();
    assertThat(machine1Row).isPresent();
    // Only the OPEN alert counts, not the RESOLVED one.
    assertThat(machine1Row.get().openAlertCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("14.1-DASHBOARD-014 P1 lifetime risk is NO_DATA when no installations exist")
  void lifetimeRiskNoDataWhenNoInstallations() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    // machine2 has no sparepart installations → NO_DATA.
    var result = dashboardService.machineDashboard(admin, null);
    var machine2Row = result.items().stream()
        .filter(r -> r.machineId().equals(chain.machine2Id()))
        .findFirst();
    assertThat(machine2Row).isPresent();
    assertThat(machine2Row.get().lifetimeRisk().status()).isEqualTo("NO_DATA");
  }

  // ---------------------------------------------------------------------------
  // Workorder dashboard
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("14.1-DASHBOARD-005 P1 workorder dashboard returns status/category counts")
  void workorderDashboardReturnsCounts() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var result = dashboardService.workorderDashboard(admin, null, null, null, null);

    assertThat(result.total()).isEqualTo(2);
    assertThat(result.byStatus()).isNotEmpty();
    var openCount = result.byStatus().stream().filter(s -> "OPEN".equals(s.status())).findFirst();
    assertThat(openCount).isPresent();
    assertThat(openCount.get().count()).isEqualTo(2);
  }

  @Test
  @DisplayName("14.1-DASHBOARD-006 P1 no workorders in scope returns zero counts (empty-scope guard)")
  void noWorkordersInScope() {
    var outsider = persistedUser("dash-wo-outsider@syncro.dev");
    // No plant assignment → empty-scope guard returns explicit empty.

    var result = dashboardService.workorderDashboard(outsider, null, null, null, null);

    assertThat(result.total()).isZero();
    assertThat(result.byStatus()).isEmpty();
    assertThat(result.byCategory()).isEmpty();
  }

  @Test
  @DisplayName("14.1-DASHBOARD-007 P1 plantId out of scope returns empty")
  void plantIdOutOfScopeReturnsEmptyWorkorders() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var result = dashboardService.workorderDashboard(admin, UUID.randomUUID(), null, null, null);

    assertThat(result.total()).isZero();
    assertThat(result.byStatus()).isEmpty();
    assertThat(result.byCategory()).isEmpty();
  }

  @Test
  @DisplayName("14.1-DASHBOARD-015 P1 byCategory includes uncategorized null grouping")
  void byCategoryIncludesUncategorized() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
    // Seed a workorder with no category_id (null).
    seedWorkOrder(chain.machine1Id(), "OPEN");

    var result = dashboardService.workorderDashboard(admin, null, null, null, null);

    // Total should be 3 (2 original + 1 uncategorized).
    assertThat(result.total()).isEqualTo(3);
    // Uncategorized workorders appear with null categoryCode/categoryLabel.
    // wo2 (already seeded with no category) + the new seedWorkOrder = 2 uncategorized
    var uncategorized = result.byCategory().stream()
        .filter(c -> c.categoryCode() == null)
        .findFirst();
    assertThat(uncategorized).isPresent();
    assertThat(uncategorized.get().count()).isEqualTo(2);
  }

  @Test
  @DisplayName("14.1-DASHBOARD-016 P1 section filter works")
  void sectionFilterWorks() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
    // machine1 is in plant1, group1 which has sectionId → section filter should return its workorders.
    var result = dashboardService.workorderDashboard(admin, null, chain.sectionId(), null, null);

    // machine1 has 2 workorders, both in the section.
    assertThat(result.total()).isEqualTo(2);
  }

  @Test
  @DisplayName("14.1-DASHBOARD-017 P1 status filter works")
  void statusFilterWorks() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var result = dashboardService.workorderDashboard(admin, null, null, WorkOrderStatus.OPEN, null);

    assertThat(result.total()).isEqualTo(2);
    assertThat(result.byStatus()).hasSize(1);
    assertThat(result.byStatus().getFirst().status()).isEqualTo("OPEN");
  }

  @Test
  @DisplayName("14.1-DASHBOARD-018 P1 categoryCode filter works")
  void categoryCodeFilterWorks() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var result = dashboardService.workorderDashboard(admin, null, null, null, "BRK");

    // Only one workorder is associated with BRK.
    assertThat(result.total()).isEqualTo(1);
  }

  @Test
  @DisplayName("14.1-DASHBOARD-019 P1 team-scope case: a team member sees cross-plant workorders")
  void teamScopeWorkorderDashboard() {
    // Grant the user access to plant1 plus a team linking to machine2.
    var tech = persistedUser("dash-wo-team@syncro.dev");
    assign(tech, chain.plant1Id());
    var team = teamService.create(superAdmin(), new CreateTeamCommand("Team " + seq.incrementAndGet(),
        Instant.now().plusSeconds(30 * 86400)));
    teamService.addMember(superAdmin(), team.id(), UUID.fromString(tech.id()));
    teamService.linkMachine(superAdmin(), team.id(), chain.machine2Id());
    entityManager.flush();

    // Seed a workorder on machine2.
    seedWorkOrder(chain.machine2Id(), "IN_PROGRESS");

    var result = dashboardService.workorderDashboard(tech, null, null, null, null);

    // Should see workorders from plant1 (via assignment) + machine2 (via team).
    assertThat(result.total()).isGreaterThanOrEqualTo(1);
    // Open from plant1 + IN_PROGRESS from machine2.
    assertThat(result.byStatus()).hasSize(2);
  }

  @Test
  @DisplayName("14.1-DASHBOARD-020 P1 403-branch: out-of-scope plantId returns empty, never 403")
  void outOfScopePlantIdReturnsEmpty_Not403() {
    var leader = persistedUser("dash-403@syncro.dev");
    assign(leader, chain.plant1Id());
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        chain.machine1Id(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER,
        TS.toInstant(), TS.toInstant()));

    // Calling with plant2 (outside scope) returns empty — no 403.
    var result = dashboardService.machineDashboard(leader, chain.plant2Id());
    assertThat(result.items()).isEmpty();

    var woResult = dashboardService.workorderDashboard(leader, chain.plant2Id(), null, null, null);
    assertThat(woResult.total()).isZero();

    var pvResult = dashboardService.preventiveDashboard(leader, chain.plant2Id());
    assertThat(pvResult.dueCount()).isZero();
  }

  // ---------------------------------------------------------------------------
  // Preventive dashboard
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("14.1-DASHBOARD-008 P1 preventive dashboard returns due/overdue counts and upcoming")
  void preventiveDashboardReturnsDueOverdue() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var result = dashboardService.preventiveDashboard(admin, null);

    assertThat(result.dueCount()).isEqualTo(1);
    assertThat(result.overdueCount()).isEqualTo(1);
    assertThat(result.upcoming()).hasSize(2);
  }

  @Test
  @DisplayName("14.1-DASHBOARD-009 P1 overdue item has derivedStatus OVERDUE label")
  void overdueItemLabeled() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var result = dashboardService.preventiveDashboard(admin, null);

    var overdue = result.upcoming().stream().filter(r -> "OVERDUE".equals(r.derivedStatus())).findFirst();
    assertThat(overdue).isPresent();
    assertThat(overdue.get().derivedStatus()).isEqualTo("OVERDUE");
  }

  @Test
  @DisplayName("14.1-DASHBOARD-010 P1 empty preventive schedules returns zero counts")
  void emptyPreventiveSchedules() {
    var outsider = persistedUser("dash-pv-empty@syncro.dev");

    var result = dashboardService.preventiveDashboard(outsider, null);

    assertThat(result.dueCount()).isZero();
    assertThat(result.overdueCount()).isZero();
    assertThat(result.upcoming()).isEmpty();
  }

  @Test
  @DisplayName("14.1-DASHBOARD-011 P1 plantId outside scope returns empty")
  void plantIdOutOfScopePreventive() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var result = dashboardService.preventiveDashboard(admin, UUID.randomUUID());

    assertThat(result.dueCount()).isZero();
    assertThat(result.overdueCount()).isZero();
    assertThat(result.upcoming()).isEmpty();
  }

  @Test
  @DisplayName("14.1-DASHBOARD-021 P1 preventive upcoming includes machine code/name and program title")
  void preventiveUpcomingIncludesMachineAndProgramDetails() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    var result = dashboardService.preventiveDashboard(admin, null);

    assertThat(result.upcoming()).isNotEmpty();
    for (var row : result.upcoming()) {
      assertThat(row.machineCode()).isNotBlank();
      assertThat(row.programTitle()).isNotBlank();
    }
  }

  // ---------------------------------------------------------------------------
  // Seed helpers
  // ---------------------------------------------------------------------------

  private void seedWorkOrder(UUID machineId, String status) {
    int s = seq.incrementAndGet();
    jdbc.update("INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at, sync_version) VALUES (?,?,?,?,?,?,?)",
        "WO-" + s, "INTERNAL", status, machineId, TS, TS, 1);
  }

  private AuthenticatedUser persistedUser(String loginIdentifier) {
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(),
        loginIdentifier,
        passwordEncoder.encode("syncro-test-password"),
        ApplicationRole.AUDITOR,
        true,
        TS.toInstant(),
        TS.toInstant()));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), ApplicationRole.AUDITOR);
  }

  private void assign(AuthenticatedUser user, UUID plantId) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plantId, TS.toInstant()));
  }

  private static AuthenticatedUser superAdmin() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
  }

  private Chain seedChain() {
    int s = seq.incrementAndGet();
    String tag = "DASH-" + s;
    UUID plant1 = UUID.randomUUID();
    UUID plant2 = UUID.randomUUID();
    UUID section = UUID.randomUUID();
    UUID group1 = UUID.randomUUID();
    UUID group2 = UUID.randomUUID();
    UUID machine1 = UUID.randomUUID();
    UUID machine2 = UUID.randomUUID();
    UUID catId = UUID.randomUUID();
    UUID programId = UUID.randomUUID();
    UUID schedule1 = UUID.randomUUID();
    UUID schedule2 = UUID.randomUUID();
    UUID wo1 = UUID.randomUUID();
    UUID wo2 = UUID.randomUUID();
    UUID inst1 = UUID.randomUUID();
    UUID sparepart1 = UUID.randomUUID();
    UUID spCat = UUID.randomUUID();
    UUID spBrand = UUID.randomUUID();
    UUID spKind = UUID.randomUUID();
    UUID spType = UUID.randomUUID();

    // Plants
    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        plant1, "P1-" + tag, "Plant 1 " + tag, TS, TS);
    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        plant2, "P2-" + tag, "Plant 2 " + tag, TS, TS);

    // Section
    jdbc.update("INSERT INTO sections (id, plant_id, code, name, active, version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        section, plant1, "MACHINERY", "Machinery", true, 0, TS, TS);

    // Machine groups
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, section_id, created_at, updated_at) VALUES (?,?,?,?,?,?)",
        group1, plant1, "Group1 " + s, section, TS, TS);
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, section_id, created_at, updated_at) VALUES (?,?,?,?,?,?)",
        group2, plant2, "Group2 " + s, section, TS, TS);

    // Machines
    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        machine1, plant1, group1, "M1-" + tag, "Machine 1 " + tag, "ACTIVE", TS, TS);
    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        machine2, plant2, group2, "M2-" + tag, "Machine 2 " + tag, "ACTIVE", TS, TS);

    // Sparepart taxonomy + sparepart + installation for machine1 (so lifetime risk can be tested)
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, created_at, updated_at) VALUES (?,?,?,?,?,?)",
        spCat, "CATEGORY", "CAT-" + tag, "Cat " + s, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        spBrand, "BRAND", "BRAND-" + tag, "Brand " + s, spCat, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        spKind, "KIND", "KIND-" + tag, "Kind " + s, spCat, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        spType, "TYPE", "TYPE-" + tag, "Type " + s, spCat, TS, TS);
    jdbc.update("INSERT INTO spareparts (id, code, name, machine_id, category_id, brand_id, kind_id, type_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        sparepart1, "SP-" + tag, "Sparepart " + s, machine1, spCat, spBrand, spKind, spType, TS, TS);
    jdbc.update("INSERT INTO machine_sparepart_installations (id, machine_id, sparepart_id, function_name, expected_production_count, baseline_counter, threshold_percentage, installed_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        inst1, machine1, sparepart1, "func1", 1000, 0, 80, TS, TS, TS);

    // Workorders — two OPEN workorders on machine1
    jdbc.update("INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at, sync_version) VALUES (?,?,?,?,?,?,?)",
        wo1, "INTERNAL", "OPEN", machine1, TS, TS, 1);
    jdbc.update("INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at, sync_version) VALUES (?,?,?,?,?,?,?)",
        wo2, "INTERNAL", "OPEN", machine1, TS, TS, 1);

    // Workorder category
    jdbc.update("INSERT INTO work_order_categories (id, code, label, created_at, updated_at) VALUES (?,?,?,?,?)",
        catId, "BRK", "Breakdown", TS, TS);
    // Associate one workorder with a category (work_orders.id is VARCHAR, so pass a String)
    jdbc.update("UPDATE work_orders SET category_id = ? WHERE id = ?", catId, wo1.toString());

    // Preventive program
    jdbc.update("INSERT INTO preventive_programs (id, machine_id, category, schedule_type, day_of_month, title, active, auto_workorder, created_by, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        programId, machine1, "MECHANICAL", "MONTHLY", 15, "Monthly Check " + s, true, false,
        UUID.randomUUID(), TS, TS);

    // Schedule 1: due today (SCHEDULED, not overdue)
    jdbc.update("INSERT INTO preventive_schedules (id, program_id, machine_id, due_date, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        schedule1, programId, machine1, LocalDate.now(), "SCHEDULED", TS, TS);

    // Schedule 2: overdue (SCHEDULED, past due)
    var pastDue = LocalDate.now().minusDays(5);
    jdbc.update("INSERT INTO preventive_schedules (id, program_id, machine_id, due_date, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        schedule2, programId, machine1, pastDue, "SCHEDULED", TS, TS);

    return new Chain(plant1, plant2, section, group1, group2, machine1, machine2, catId, programId,
        schedule1, schedule2, wo1, wo2, inst1);
  }

  private record Chain(UUID plant1Id, UUID plant2Id, UUID sectionId, UUID group1Id, UUID group2Id,
      UUID machine1Id, UUID machine2Id, UUID catId, UUID programId, UUID schedule1Id, UUID schedule2Id,
      UUID wo1Id, UUID wo2Id, UUID installation1Id) {
  }
}