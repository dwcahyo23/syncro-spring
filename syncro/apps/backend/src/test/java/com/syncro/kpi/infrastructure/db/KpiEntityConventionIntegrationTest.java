package com.syncro.kpi.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.kpi.domain.KpiAggregateRefreshStatus;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 15-2 convention proof for {@code com.syncro.kpi.infrastructure.db} (blueprint
 * G1–G7): validates every KPI entity against the V1 schema via context boot and
 * round-trips targets, monthlies, and the refresh log — the BigDecimal/Instant/
 * nullable semantics of the I/O matrix. All values are backend-materialized: no
 * floating-point, month normalized to the first of the month.
 */
class KpiEntityConventionIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-08-31T08:00:00Z");
  private static final Instant T1 = Instant.parse("2026-08-31T09:00:00Z");
  private static final LocalDate AUG = LocalDate.of(2026, 8, 1);

  @Autowired
  private PlantRepository plants;
  @Autowired
  private AuthUserRepository users;
  @Autowired
  private com.syncro.masterdata.infrastructure.MachineGroupRepository machineGroups;
  @Autowired
  private com.syncro.machine.infrastructure.MachineRepository machines;
  @Autowired
  private KpiTargetRepository targets;
  @Autowired
  private KpiMonthlyBreakdownRepository breakdowns;
  @Autowired
  private KpiMtbfMonthlyRepository mtbfMonthlies;
  @Autowired
  private KpiMttrMonthlyRepository mttrMonthlies;
  @Autowired
  private KpiMarMonthlyRepository marMonthlies;
  @Autowired
  private KpiTechnicianMonthlyRepository technicianMonthlies;
  @Autowired
  private KpiPmCompletionMonthlyRepository pmCompletionMonthlies;
  @Autowired
  private KpiAggregateRefreshLogRepository refreshLogs;

  private PlantEntity plant() {
    var code = "K" + UUID.randomUUID().toString().substring(0, 8);
    return plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), code, "Plant " + code, T0, T0));
  }

  /** kpi_mtbf_monthlies.machine_id is a real machines FK in V1 — needs a persisted machine. */
  private UUID persistedMachineId(PlantEntity plant) {
    var group = machineGroups.saveAndFlush(new com.syncro.masterdata.infrastructure.MachineGroupEntity(
        UUID.randomUUID(), plant, "Group", T0, T0));
    var machine = machines.saveAndFlush(new com.syncro.machine.infrastructure.MachineEntity(
        UUID.randomUUID(), plant, group, "MC-" + UUID.randomUUID().toString().substring(0, 8),
        "Machine", com.syncro.machine.domain.MachineStatus.ACTIVE, null, null, null, null, T0, T0));
    return machine.getId();
  }

  @Test
  void kpiTargetRoundTripWithBigDecimalAndNullables() {
    var plant = plant();
    var target = targets.saveAndFlush(new KpiTargetEntity(
        UUID.randomUUID(), plant.getId(), AUG, 2, new BigDecimal("30.50"),
        new BigDecimal("120.00"), new BigDecimal("95.25"), null, null, T0, T0));

    var reloaded = targets.findById(target.getId()).orElseThrow();
    assertThat(reloaded.getMonth()).isEqualTo(AUG);
    assertThat(reloaded.getMonthlyBreakdownTarget()).isEqualTo(2);
    assertThat(reloaded.getMtbfTargetDays()).isEqualByComparingTo(new BigDecimal("30.5"));
    assertThat(reloaded.getMttrTargetMinutes()).isEqualByComparingTo(new BigDecimal("120"));
    assertThat(reloaded.getOeeQualityPercent()).isEqualByComparingTo(new BigDecimal("95.25"));
    assertThat(reloaded.getOeePerformancePercent()).isNull();
    assertThat(reloaded.getCreatedBy()).isNull();
    assertThat(targets.findByPlantIdAndMonth(plant.getId(), AUG)).contains(reloaded);
  }

  @Test
  void monthlyMaterializationsRoundTrip() {
    var plant = plant();
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(), "kpi-" + UUID.randomUUID() + "@syncro.test", "hash",
        ApplicationRole.TECHNICIAN, true, T0, T0));
    var machineId = persistedMachineId(plant);

    var breakdown = breakdowns.saveAndFlush(new KpiMonthlyBreakdownEntity(
        UUID.randomUUID(), plant.getId(), AUG, 3, T0, T0));
    var mtbf = mtbfMonthlies.saveAndFlush(new KpiMtbfMonthlyEntity(
        UUID.randomUUID(), plant.getId(), machineId, AUG, new BigDecimal("12.75"), T0, T0));
    var mttr = mttrMonthlies.saveAndFlush(new KpiMttrMonthlyEntity(
        UUID.randomUUID(), plant.getId(), AUG, new BigDecimal("90.10"),
        new BigDecimal("60.20"), T0, T0));
    var mar = marMonthlies.saveAndFlush(new KpiMarMonthlyEntity(
        UUID.randomUUID(), plant.getId(), AUG, 600, 45, new BigDecimal("92.50"),
        "PARTIAL", "telemetry gap on 2 machines", T0, T0));
    var tech = technicianMonthlies.saveAndFlush(new KpiTechnicianMonthlyEntity(
        UUID.randomUUID(), plant.getId(), user.getId(), AUG, new BigDecimal("4.33"), 12,
        new BigDecimal("87.00"), T0, T0));
    var pm = pmCompletionMonthlies.saveAndFlush(new KpiPmCompletionMonthlyEntity(
        UUID.randomUUID(), plant.getId(), AUG, new BigDecimal("96.15"), 25, 26, "COMPLETE",
        null, T0, T0));

    assertThat(breakdowns.findById(breakdown.getId()).orElseThrow().getCount()).isEqualTo(3);
    assertThat(breakdowns.findByPlantIdAndMonth(plant.getId(), AUG)).contains(breakdown);

    var reloadedMtbf = mtbfMonthlies.findById(mtbf.getId()).orElseThrow();
    assertThat(reloadedMtbf.getMachineId()).isEqualTo(machineId);
    assertThat(reloadedMtbf.getMtbfDays()).isEqualByComparingTo(new BigDecimal("12.75"));
    assertThat(mtbfMonthlies.findByMachineIdAndMonth(machineId, AUG)).contains(reloadedMtbf);

    var reloadedMttr = mttrMonthlies.findById(mttr.getId()).orElseThrow();
    assertThat(reloadedMttr.getWallClockMttrMinutes()).isEqualByComparingTo(new BigDecimal("90.1"));
    assertThat(reloadedMttr.getActualWorkingMttrMinutes()).isEqualByComparingTo(new BigDecimal("60.2"));

    var reloadedMar = marMonthlies.findById(mar.getId()).orElseThrow();
    assertThat(reloadedMar.getPlannedAvailableMinutes()).isEqualTo(600);
    assertThat(reloadedMar.getDowntimeMinutes()).isEqualTo(45);
    assertThat(reloadedMar.getMarPercent()).isEqualByComparingTo(new BigDecimal("92.5"));
    assertThat(reloadedMar.getSourceStatus()).isEqualTo("PARTIAL");
    assertThat(reloadedMar.getSourceMessage()).isEqualTo("telemetry gap on 2 machines");

    var reloadedTech = technicianMonthlies.findById(tech.getId()).orElseThrow();
    assertThat(reloadedTech.getTechnicianId()).isEqualTo(user.getId());
    assertThat(reloadedTech.getAverageRating()).isEqualByComparingTo(new BigDecimal("4.33"));
    assertThat(reloadedTech.getTotalWo()).isEqualTo(12);
    assertThat(reloadedTech.getFirstTimeFixRate()).isEqualByComparingTo(new BigDecimal("87"));
    assertThat(technicianMonthlies.findByPlantIdAndTechnicianIdAndMonth(
        plant.getId(), user.getId(), AUG)).contains(reloadedTech);

    var reloadedPm = pmCompletionMonthlies.findById(pm.getId()).orElseThrow();
    assertThat(reloadedPm.getCompletedCount()).isEqualTo(25);
    assertThat(reloadedPm.getPlannedCount()).isEqualTo(26);
    assertThat(reloadedPm.getCompletionRate()).isEqualByComparingTo(new BigDecimal("96.15"));
    assertThat(reloadedPm.getSourceStatus()).isEqualTo("COMPLETE");
    assertThat(reloadedPm.getSourceMessage()).isNull();
  }

  @Test
  void aggregateRefreshLogLifecycleRoundTrip() {
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var log = refreshLogs.saveAndFlush(new KpiAggregateRefreshLogEntity(
        UUID.randomUUID(), "mtbf:" + suffix, T0, KpiAggregateRefreshStatus.RUNNING, null));

    var running = refreshLogs.findByRefreshKey("mtbf:" + suffix).orElseThrow();
    assertThat(running.getStatus()).isEqualTo(KpiAggregateRefreshStatus.RUNNING);
    assertThat(running.getMessage()).isNull();

    running.finish(KpiAggregateRefreshStatus.SUCCESS, "3 rows", T1);
    refreshLogs.saveAndFlush(running);

    var reloaded = refreshLogs.findById(log.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(KpiAggregateRefreshStatus.SUCCESS);
    assertThat(reloaded.getMessage()).isEqualTo("3 rows");
    assertThat(reloaded.getRefreshedAt()).isEqualTo(T1);
  }
}
