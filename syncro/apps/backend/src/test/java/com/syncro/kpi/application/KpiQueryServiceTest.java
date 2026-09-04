package com.syncro.kpi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.kpi.domain.KpiAggregateRefreshStatus;
import com.syncro.kpi.domain.KpiType;
import com.syncro.kpi.infrastructure.db.KpiAggregateRefreshLogEntity;
import com.syncro.kpi.infrastructure.db.KpiAggregateRefreshLogRepository;
import com.syncro.kpi.infrastructure.db.KpiMarMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiMarMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiMonthlyBreakdownEntity;
import com.syncro.kpi.infrastructure.db.KpiMonthlyBreakdownRepository;
import com.syncro.kpi.infrastructure.db.KpiMtbfMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiMtbfMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiMttrMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiMttrMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiPmCompletionMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiPmCompletionMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiTechnicianMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiTargetEntity;
import com.syncro.kpi.infrastructure.db.KpiTargetRepository;
import com.syncro.kpi.infrastructure.db.KpiTechnicianMonthlyRepository;
import com.syncro.org.application.OperationalScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 20-1 unit tests for {@link KpiQueryService}: organizational-scope filtering
 * (leaders see their groups, managers their plants, global roles everything), the
 * actual-vs-target join, and the explicit insufficient-data state when a month has no
 * materialized row (never a fabricated zero).
 */
@ExtendWith(MockitoExtension.class)
class KpiQueryServiceTest {

  private static final LocalDate AUG = LocalDate.of(2026, 8, 1);
  private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

  private final UUID plantA = UUID.randomUUID();
  private final UUID plantB = UUID.randomUUID();
  private final UUID machineA = UUID.randomUUID();
  private final UUID machineB = UUID.randomUUID();
  private final UUID groupA = UUID.randomUUID();

  @Mock
  private KpiScopeService scopeService;
  @Mock
  private KpiSourceDataReader source;
  @Mock
  private KpiMonthlyBreakdownRepository breakdowns;
  @Mock
  private KpiMtbfMonthlyRepository mtbfMonthlies;
  @Mock
  private KpiMttrMonthlyRepository mttrMonthlies;
  @Mock
  private KpiMarMonthlyRepository marMonthlies;
  @Mock
  private KpiTechnicianMonthlyRepository technicianMonthlies;
  @Mock
  private KpiPmCompletionMonthlyRepository pmCompletionMonthlies;
  @Mock
  private KpiTargetRepository targets;
  @Mock
  private KpiAggregateRefreshLogRepository refreshLogs;

  private KpiQueryService service;
  private final AuthenticatedUser leader = new AuthenticatedUser(
      UUID.randomUUID().toString(), "leader@syncro.test", ApplicationRole.SECTION_LEADER);

  @BeforeEach
  void setUp() {
    service = new KpiQueryService(scopeService, source, breakdowns, mtbfMonthlies, mttrMonthlies,
        marMonthlies, technicianMonthlies, pmCompletionMonthlies, targets, refreshLogs);
  }

  @Test
  @DisplayName("20-1-SCOPE-001 P0: leader sees only MTBF rows of machines in their groups")
  void leaderSeesOnlyScopedMachines() {
    // Group-only scope: the leader has no plant assignment — visibility comes solely
    // from the machine-group dimension (AD-2: leaders see their groups).
    var scope = new OperationalScope(Set.of(), Set.of(groupA), Set.of());
    when(scopeService.derive(leader)).thenReturn(scope);
    when(scopeService.groupIds(scope)).thenReturn(List.of(groupA));
    when(source.findMachineIdsByGroupIds(List.of(groupA))).thenReturn(List.of(machineA));
    when(mtbfMonthlies.findByMonth(AUG)).thenReturn(List.of(
        new KpiMtbfMonthlyEntity(UUID.randomUUID(), plantA, machineA, AUG,
            new BigDecimal("12.00"), T0, T0),
        new KpiMtbfMonthlyEntity(UUID.randomUUID(), plantA, machineB, AUG,
            new BigDecimal("30.00"), T0, T0)));
    when(refreshLogs.findByRefreshKey("mtbf:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(leader, KpiType.MTBF, AUG, null);

    assertThat(response.status()).isEqualTo(KpiQueryService.STATUS_AVAILABLE);
    assertThat(response.mtbfRows()).hasSize(1);
    assertThat(response.mtbfRows().get(0).machineId()).isEqualTo(machineA);
  }

  @Test
  @DisplayName("20-1-SCOPE-002 P0: out-of-scope plant request is denied server-side")
  void outOfScopePlantDenied() {
    var scope = new OperationalScope(Set.of(plantA), Set.of(), Set.of());
    when(scopeService.derive(leader)).thenReturn(scope);
    when(scopeService.plantVisible(scope, plantB)).thenReturn(false);

    assertThatThrownBy(() -> service.materialized(leader, KpiType.MTBF, AUG, plantB))
        .isInstanceOf(KpiQueryService.KpiPlantForbiddenException.class);
  }

  @Test
  @DisplayName("20-1-SCOPE-003 P0: SUPER_ADMIN (null plantIds) sees every plant's rows")
  void superAdminSeesAllPlants() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);
    var scope = new OperationalScope(null, Set.of(), Set.of());
    when(scopeService.derive(admin)).thenReturn(scope);
    when(mttrMonthlies.findByMonth(AUG)).thenReturn(List.of(
        new KpiMttrMonthlyEntity(UUID.randomUUID(), plantA, AUG, new BigDecimal("90.00"),
            new BigDecimal("60.00"), T0, T0),
        new KpiMttrMonthlyEntity(UUID.randomUUID(), plantB, AUG, new BigDecimal("45.00"),
            new BigDecimal("30.00"), T0, T0)));
    when(refreshLogs.findByRefreshKey("mttr:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.MTTR, AUG, null);

    assertThat(response.mttrRows()).hasSize(2);
  }

  @Test
  @DisplayName("20-1-TARGET-001 P0: materialized read joins the plant/month target")
  void targetJoin() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);
    var scope = new OperationalScope(null, Set.of(), Set.of());
    when(scopeService.derive(admin)).thenReturn(scope);
    when(scopeService.plantVisible(scope, plantA)).thenReturn(true);
    when(mttrMonthlies.findByMonth(AUG)).thenReturn(List.of(
        new KpiMttrMonthlyEntity(UUID.randomUUID(), plantA, AUG, new BigDecimal("90.00"),
            new BigDecimal("60.00"), T0, T0)));
    when(targets.findByPlantIdAndMonth(plantA, AUG)).thenReturn(Optional.of(new KpiTargetEntity(
        UUID.randomUUID(), plantA, AUG, 2, new BigDecimal("30.50"), new BigDecimal("120.00"),
        null, null, null, T0, T0)));
    when(refreshLogs.findByRefreshKey("mttr:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.MTTR, AUG, plantA);

    assertThat(response.target()).isNotNull();
    assertThat(response.target().mttrTargetMinutes()).isEqualByComparingTo(new BigDecimal("120.00"));
    assertThat(response.mttrRows().get(0).wallClockMinutes()).isEqualByComparingTo(new BigDecimal("90.00"));
  }

  @Test
  @DisplayName("20-1-INSUF-001 P0: month with no materialized row → INSUFFICIENT_DATA, empty lists")
  void missingMonthInsufficientData() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);
    var scope = new OperationalScope(null, Set.of(), Set.of());
    when(scopeService.derive(admin)).thenReturn(scope);
    when(marMonthlies.findByMonth(AUG)).thenReturn(List.of());
    when(refreshLogs.findByRefreshKey("mar:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.MAR, AUG, null);

    assertThat(response.status()).isEqualTo(KpiQueryService.STATUS_INSUFFICIENT_DATA);
    assertThat(response.marRows()).isEmpty();
  }

  @Test
  @DisplayName("20-1-INSUF-002 P1: INSUFFICIENT_DATA source_status row is returned as-is (not zeroed)")
  void insufficientSourceStatusRowPassesThrough() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);
    var scope = new OperationalScope(null, Set.of(), Set.of());
    when(scopeService.derive(admin)).thenReturn(scope);
    when(marMonthlies.findByMonth(AUG)).thenReturn(List.of(new KpiMarMonthlyEntity(
        UUID.randomUUID(), plantA, AUG, 0, 0, null, "INSUFFICIENT_DATA",
        "no telemetry availability source for month 2026-08", T0, T0)));
    when(refreshLogs.findByRefreshKey("mar:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.MAR, AUG, null);

    assertThat(response.marRows()).hasSize(1);
    assertThat(response.marRows().get(0).marPercent()).isNull();
    assertThat(response.marRows().get(0).sourceStatus()).isEqualTo("INSUFFICIENT_DATA");
  }

  @Test
  @DisplayName("20-1-REFRESH-001 P1: refresh evidence is attached to the response")
  void refreshEvidenceAttached() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);
    var scope = new OperationalScope(null, Set.of(), Set.of());
    when(scopeService.derive(admin)).thenReturn(scope);
    when(breakdowns.findByMonth(AUG)).thenReturn(List.of());
    when(refreshLogs.findByRefreshKey("breakdown:2026-08")).thenReturn(Optional.of(
        new KpiAggregateRefreshLogEntity(UUID.randomUUID(), "breakdown:2026-08", T0,
            KpiAggregateRefreshStatus.SUCCESS, "traceId=t rows=1")));

    var response = service.materialized(admin, KpiType.BREAKDOWN, AUG, null);

    assertThat(response.refresh()).isNotNull();
    assertThat(response.refresh().status()).isEqualTo(KpiAggregateRefreshStatus.SUCCESS);
    assertThat(response.refresh().message()).contains("traceId=t");
  }

  // ---------------------------------------------------------------------------
  // Story 20-2: server-side actual-vs-target verdict matrix
  // ---------------------------------------------------------------------------

  private final AuthenticatedUser admin = new AuthenticatedUser(
      UUID.randomUUID().toString(), "admin@syncro.test", ApplicationRole.SUPER_ADMIN);

  private OperationalScope adminScope() {
    var scope = new OperationalScope(null, Set.of(), Set.of());
    when(scopeService.derive(admin)).thenReturn(scope);
    return scope;
  }

  private static KpiTargetEntity target(UUID plant, Integer breakdown, BigDecimal mtbfDays,
      BigDecimal mttrMinutes) {
    return new KpiTargetEntity(UUID.randomUUID(), plant, AUG, breakdown, mtbfDays, mttrMinutes,
        null, null, null, T0, T0);
  }

  @Test
  @DisplayName("20-2-VERDICT-001 P0: MTBF 10.42 days vs target 8.00 → ON_TARGET (higher-better)")
  void mtbfAboveTargetIsOnTarget() {
    adminScope();
    when(mtbfMonthlies.findByMonth(AUG)).thenReturn(List.of(new KpiMtbfMonthlyEntity(
        UUID.randomUUID(), plantA, machineA, AUG, new BigDecimal("10.42"), T0, T0)));
    when(targets.findByPlantIdAndMonth(plantA, AUG)).thenReturn(Optional.of(
        target(plantA, null, new BigDecimal("8.00"), null)));
    when(refreshLogs.findByRefreshKey("mtbf:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.MTBF, AUG, null);

    var row = response.mtbfRows().get(0);
    assertThat(row.mtbfDays()).isEqualByComparingTo(new BigDecimal("10.42"));
    assertThat(row.targetValue()).isEqualByComparingTo(new BigDecimal("8.00"));
    assertThat(row.targetStatus()).isEqualTo(KpiQueryService.VERDICT_ON_TARGET);
  }

  @Test
  @DisplayName("20-2-VERDICT-002 P0: MTBF below target → BELOW_TARGET; equal counts as ON_TARGET")
  void mtbfBelowTargetIsBelowTarget() {
    adminScope();
    when(mtbfMonthlies.findByMonth(AUG)).thenReturn(List.of(
        new KpiMtbfMonthlyEntity(UUID.randomUUID(), plantA, machineA, AUG,
            new BigDecimal("5.00"), T0, T0),
        new KpiMtbfMonthlyEntity(UUID.randomUUID(), plantA, machineB, AUG,
            new BigDecimal("8.00"), T0, T0)));
    when(targets.findByPlantIdAndMonth(plantA, AUG)).thenReturn(Optional.of(
        target(plantA, null, new BigDecimal("8.00"), null)));
    when(refreshLogs.findByRefreshKey("mtbf:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.MTBF, AUG, null);

    // Rows sort by machineId (random UUIDs) — assert per machine, not by index.
    assertThat(response.mtbfRows().stream()
        .filter(r -> r.machineId().equals(machineA)).findFirst().orElseThrow().targetStatus())
        .isEqualTo(KpiQueryService.VERDICT_BELOW_TARGET);
    assertThat(response.mtbfRows().stream()
        .filter(r -> r.machineId().equals(machineB)).findFirst().orElseThrow().targetStatus())
        .isEqualTo(KpiQueryService.VERDICT_ON_TARGET);
  }

  @Test
  @DisplayName("20-2-VERDICT-003 P0: MTTR 75 min vs target 60 → ABOVE_TARGET (lower-better)")
  void mttrWorseThanTargetIsAboveTarget() {
    adminScope();
    when(mttrMonthlies.findByMonth(AUG)).thenReturn(List.of(new KpiMttrMonthlyEntity(
        UUID.randomUUID(), plantA, AUG, new BigDecimal("75.00"), new BigDecimal("75.00"),
        T0, T0)));
    when(targets.findByPlantIdAndMonth(plantA, AUG)).thenReturn(Optional.of(
        target(plantA, null, null, new BigDecimal("60.00"))));
    when(refreshLogs.findByRefreshKey("mttr:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.MTTR, AUG, null);

    var row = response.mttrRows().get(0);
    assertThat(row.targetValue()).isEqualByComparingTo(new BigDecimal("60.00"));
    assertThat(row.targetStatus()).isEqualTo(KpiQueryService.VERDICT_ABOVE_TARGET);
  }

  @Test
  @DisplayName("20-2-VERDICT-004 P0: MTTR at/below target → ON_TARGET; verdict follows actual-working when present")
  void mttrOnTargetAndActualWorkingPreferred() {
    adminScope();
    // wall-clock 90 would miss a 60 target; actual-working 45 meets it — the verdict
    // follows the reference dashboard's actual-working ?? wall-clock precedence.
    when(mttrMonthlies.findByMonth(AUG)).thenReturn(List.of(new KpiMttrMonthlyEntity(
        UUID.randomUUID(), plantA, AUG, new BigDecimal("90.00"), new BigDecimal("45.00"),
        T0, T0)));
    when(targets.findByPlantIdAndMonth(plantA, AUG)).thenReturn(Optional.of(
        target(plantA, null, null, new BigDecimal("60.00"))));
    when(refreshLogs.findByRefreshKey("mttr:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.MTTR, AUG, null);

    assertThat(response.mttrRows().get(0).targetStatus())
        .isEqualTo(KpiQueryService.VERDICT_ON_TARGET);
  }

  @Test
  @DisplayName("20-2-VERDICT-005 P0: no target configured → NO_TARGET, actual still shown")
  void missingTargetIsNoTarget() {
    adminScope();
    when(mtbfMonthlies.findByMonth(AUG)).thenReturn(List.of(new KpiMtbfMonthlyEntity(
        UUID.randomUUID(), plantA, machineA, AUG, new BigDecimal("12.00"), T0, T0)));
    when(targets.findByPlantIdAndMonth(plantA, AUG)).thenReturn(Optional.empty());
    when(refreshLogs.findByRefreshKey("mtbf:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.MTBF, AUG, null);

    var row = response.mtbfRows().get(0);
    assertThat(row.mtbfDays()).isEqualByComparingTo(new BigDecimal("12.00"));
    assertThat(row.targetValue()).isNull();
    assertThat(row.targetStatus()).isEqualTo(KpiQueryService.VERDICT_NO_TARGET);
  }

  @Test
  @DisplayName("20-2-VERDICT-006 P0: target present but actual null → INSUFFICIENT_DATA, never a fabricated verdict")
  void nullActualIsInsufficientData() {
    adminScope();
    when(mttrMonthlies.findByMonth(AUG)).thenReturn(List.of(new KpiMttrMonthlyEntity(
        UUID.randomUUID(), plantA, AUG, null, null, T0, T0)));
    when(targets.findByPlantIdAndMonth(plantA, AUG)).thenReturn(Optional.of(
        target(plantA, null, null, new BigDecimal("60.00"))));
    when(refreshLogs.findByRefreshKey("mttr:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.MTTR, AUG, null);

    var row = response.mttrRows().get(0);
    assertThat(row.wallClockMinutes()).isNull();
    assertThat(row.targetStatus()).isEqualTo(KpiQueryService.STATUS_INSUFFICIENT_DATA);
  }

  @Test
  @DisplayName("20-2-JOIN-001 P0: per-row join without plantId param — each plant row carries its own verdict")
  void perRowJoinWithoutPlantIdParam() {
    adminScope();
    when(mttrMonthlies.findByMonth(AUG)).thenReturn(List.of(
        new KpiMttrMonthlyEntity(UUID.randomUUID(), plantA, AUG, new BigDecimal("45.00"),
            new BigDecimal("45.00"), T0, T0),
        new KpiMttrMonthlyEntity(UUID.randomUUID(), plantB, AUG, new BigDecimal("90.00"),
            new BigDecimal("90.00"), T0, T0)));
    // Only plantA has a target — plantB's row must still render with NO_TARGET.
    when(targets.findByPlantIdAndMonth(plantA, AUG)).thenReturn(Optional.of(
        target(plantA, null, null, new BigDecimal("60.00"))));
    when(targets.findByPlantIdAndMonth(plantB, AUG)).thenReturn(Optional.empty());
    when(refreshLogs.findByRefreshKey("mttr:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.MTTR, AUG, null);

    assertThat(response.target()).isNull(); // raw target view stays param-driven (20-1 shape)
    assertThat(response.mttrRows()).hasSize(2);
    // Rows sort by plantId (random UUIDs) — assert per plant, not by index.
    assertThat(response.mttrRows().stream()
        .filter(r -> r.plantId().equals(plantA)).findFirst().orElseThrow().targetStatus())
        .isEqualTo(KpiQueryService.VERDICT_ON_TARGET);
    assertThat(response.mttrRows().stream()
        .filter(r -> r.plantId().equals(plantB)).findFirst().orElseThrow().targetStatus())
        .isEqualTo(KpiQueryService.VERDICT_NO_TARGET);
  }

  @Test
  @DisplayName("20-2-JOIN-002 P1: one target lookup per distinct plant, not per row")
  void targetLookupDedupedPerPlant() {
    adminScope();
    when(mtbfMonthlies.findByMonth(AUG)).thenReturn(List.of(
        new KpiMtbfMonthlyEntity(UUID.randomUUID(), plantA, machineA, AUG,
            new BigDecimal("10.00"), T0, T0),
        new KpiMtbfMonthlyEntity(UUID.randomUUID(), plantA, machineB, AUG,
            new BigDecimal("20.00"), T0, T0)));
    when(targets.findByPlantIdAndMonth(plantA, AUG)).thenReturn(Optional.of(
        target(plantA, null, new BigDecimal("8.00"), null)));
    when(refreshLogs.findByRefreshKey("mtbf:2026-08")).thenReturn(Optional.empty());

    service.materialized(admin, KpiType.MTBF, AUG, null);

    verify(targets, times(1)).findByPlantIdAndMonth(plantA, AUG);
  }

  @Test
  @DisplayName("20-2-VERDICT-007 P0: breakdown count vs monthly target (lower-better)")
  void breakdownVerdictLowerBetter() {
    adminScope();
    when(breakdowns.findByMonth(AUG)).thenReturn(List.of(
        new KpiMonthlyBreakdownEntity(UUID.randomUUID(), plantA, AUG, 2, T0, T0),
        new KpiMonthlyBreakdownEntity(UUID.randomUUID(), plantB, AUG, 5, T0, T0)));
    when(targets.findByPlantIdAndMonth(plantA, AUG)).thenReturn(Optional.of(
        target(plantA, 3, null, null)));
    when(targets.findByPlantIdAndMonth(plantB, AUG)).thenReturn(Optional.of(
        target(plantB, 3, null, null)));
    when(refreshLogs.findByRefreshKey("breakdown:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.BREAKDOWN, AUG, null);

    // Rows sort by plantId (random UUIDs) — assert per plant, not by index.
    var rowA = response.breakdownRows().stream()
        .filter(r -> r.plantId().equals(plantA)).findFirst().orElseThrow();
    assertThat(rowA.targetValue()).isEqualByComparingTo(new BigDecimal("3"));
    assertThat(rowA.targetStatus()).isEqualTo(KpiQueryService.VERDICT_ON_TARGET);
    assertThat(response.breakdownRows().stream()
        .filter(r -> r.plantId().equals(plantB)).findFirst().orElseThrow().targetStatus())
        .isEqualTo(KpiQueryService.VERDICT_ABOVE_TARGET);
  }

  @Test
  @DisplayName("20-2-VERDICT-008 P1: technician rows always NO_TARGET (no target column); actuals still render")
  void technicianRowsAlwaysNoTarget() {
    adminScope();
    when(technicianMonthlies.findByMonth(AUG)).thenReturn(List.of(
        new KpiTechnicianMonthlyEntity(UUID.randomUUID(), plantA, UUID.randomUUID(), AUG,
            new BigDecimal("4.50"), 7, new BigDecimal("85.00"), T0, T0)));
    when(refreshLogs.findByRefreshKey("technician:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.TECHNICIAN, AUG, null);

    var row = response.technicianRows().get(0);
    assertThat(row.averageRating()).isEqualByComparingTo(new BigDecimal("4.50"));
    assertThat(row.targetValue()).isNull();
    assertThat(row.targetStatus()).isEqualTo(KpiQueryService.VERDICT_NO_TARGET);
    verifyNoInteractions(targets);
  }

  @Test
  @DisplayName("20-2-VERDICT-009 P1: MAR/PM rows carry NO_TARGET (kpi_target has no comparable column)")
  void marAndPmRowsAreNoTarget() {
    adminScope();
    when(marMonthlies.findByMonth(AUG)).thenReturn(List.of(new KpiMarMonthlyEntity(
        UUID.randomUUID(), plantA, AUG, 10_000, 500, new BigDecimal("95.00"), "AVAILABLE",
        null, T0, T0)));
    when(refreshLogs.findByRefreshKey("mar:2026-08")).thenReturn(Optional.empty());

    var mar = service.materialized(admin, KpiType.MAR, AUG, null);
    assertThat(mar.marRows().get(0).marPercent()).isEqualByComparingTo(new BigDecimal("95.00"));
    assertThat(mar.marRows().get(0).targetStatus()).isEqualTo(KpiQueryService.VERDICT_NO_TARGET);

    when(pmCompletionMonthlies.findByMonth(AUG)).thenReturn(List.of(
        new KpiPmCompletionMonthlyEntity(UUID.randomUUID(), plantA, AUG, new BigDecimal("90.00"),
            9, 10, "AVAILABLE", null, T0, T0)));
    when(refreshLogs.findByRefreshKey("pm-completion:2026-08")).thenReturn(Optional.empty());

    var pm = service.materialized(admin, KpiType.PM_COMPLETION, AUG, null);
    assertThat(pm.pmCompletionRows().get(0).completionRate())
        .isEqualByComparingTo(new BigDecimal("90.00"));
    assertThat(pm.pmCompletionRows().get(0).targetStatus())
        .isEqualTo(KpiQueryService.VERDICT_NO_TARGET);
  }

  @Test
  @DisplayName("20-2-VERDICT-010 P0: lower-better equality boundary — actual == target → ON_TARGET")
  void lowerBetterEqualityIsOnTarget() {
    adminScope();
    // Review 20-2: VERDICT-007 only covers strictly-below (2 vs 3). Flipping `<=` to `<`
    // in verdict() must fail here — plants exactly at target are the common "met the goal"
    // state and must not render as a miss.
    when(breakdowns.findByMonth(AUG)).thenReturn(List.of(
        new KpiMonthlyBreakdownEntity(UUID.randomUUID(), plantA, AUG, 3, T0, T0)));
    when(targets.findByPlantIdAndMonth(plantA, AUG)).thenReturn(Optional.of(
        target(plantA, 3, null, null)));
    when(refreshLogs.findByRefreshKey("breakdown:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.BREAKDOWN, AUG, null);

    assertThat(response.breakdownRows().get(0).targetStatus())
        .isEqualTo(KpiQueryService.VERDICT_ON_TARGET);
  }

  @Test
  @DisplayName("20-2-VERDICT-011 P0: MTTR with null actual-working falls back to wall-clock")
  void mttrWallClockFallbackWhenActualWorkingNull() {
    adminScope();
    // Review 20-2: every other MTTR fixture has both variants equal or both null, so the
    // `actualWorking ?? wallClock` precedence is undistinguishable. Wall-clock 75 vs a 60
    // target must yield ABOVE_TARGET — deleting the fallback would flip this to
    // INSUFFICIENT_DATA and silently downgrade real data.
    when(mttrMonthlies.findByMonth(AUG)).thenReturn(List.of(new KpiMttrMonthlyEntity(
        UUID.randomUUID(), plantA, AUG, new BigDecimal("75.00"), null, T0, T0)));
    when(targets.findByPlantIdAndMonth(plantA, AUG)).thenReturn(Optional.of(
        target(plantA, null, null, new BigDecimal("60.00"))));
    when(refreshLogs.findByRefreshKey("mttr:2026-08")).thenReturn(Optional.empty());

    var response = service.materialized(admin, KpiType.MTTR, AUG, null);

    var row = response.mttrRows().get(0);
    assertThat(row.actualWorkingMinutes()).isNull();
    assertThat(row.targetStatus()).isEqualTo(KpiQueryService.VERDICT_ABOVE_TARGET);
  }
}
