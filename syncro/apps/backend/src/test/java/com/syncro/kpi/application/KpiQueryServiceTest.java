package com.syncro.kpi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.kpi.domain.KpiAggregateRefreshStatus;
import com.syncro.kpi.domain.KpiType;
import com.syncro.kpi.infrastructure.db.KpiAggregateRefreshLogEntity;
import com.syncro.kpi.infrastructure.db.KpiAggregateRefreshLogRepository;
import com.syncro.kpi.infrastructure.db.KpiMarMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiMarMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiMonthlyBreakdownRepository;
import com.syncro.kpi.infrastructure.db.KpiMtbfMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiMtbfMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiMttrMonthlyEntity;
import com.syncro.kpi.infrastructure.db.KpiMttrMonthlyRepository;
import com.syncro.kpi.infrastructure.db.KpiPmCompletionMonthlyRepository;
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
}
