package com.syncro.kpi.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.kpi.application.KpiMaterializationService;
import com.syncro.kpi.domain.KpiAggregateRefreshStatus;
import com.syncro.kpi.domain.KpiSourceStatus;
import com.syncro.kpi.domain.KpiType;
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
 *
 * <p>Story 20-1 extension: an end-to-end refresh round-trip against the real database —
 * seeded workorder/work-log/rating/PM data through the actual source adapter and
 * {@link KpiMaterializationService}, asserting materialized rows, the refresh-log
 * RUNNING→SUCCESS evidence, and idempotent re-runs (the spec's I/O matrix rows).
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
  @Autowired
  private KpiMaterializationService materialization;
  @Autowired
  private com.syncro.maintenance.infrastructure.db.WorkOrderRepository workOrders;
  @Autowired
  private com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryRepository statusHistory;
  @Autowired
  private com.syncro.maintenance.infrastructure.db.WorkLogRepository workLogs;
  @Autowired
  private com.syncro.maintenance.infrastructure.db.WorkLogRatingRepository workLogRatings;
  @Autowired
  private com.syncro.maintenance.infrastructure.db.WorkLogRatingCriterionRepository ratingCriteria;
  @Autowired
  private com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository categories;
  @Autowired
  private com.syncro.maintenance.preventive.infrastructure.db.PmWorkOrderRepository pmWorkOrders;
  @Autowired
  private com.syncro.maintenance.preventive.infrastructure.db.PmExecutionRepository pmExecutions;

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
        "COMPLETE", "telemetry gap on 2 machines", T0, T0));
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
    assertThat(reloadedMar.getSourceStatus()).isEqualTo("COMPLETE");
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

  // ---------------------------------------------------------------------------
  // Story 20-1: end-to-end monthly refresh against the real database
  // ---------------------------------------------------------------------------

  @Test
  void monthlyRefreshMaterializesAllKpiTypesAndIsIdempotent() {
    var plant = plant();
    var machine = machine(plant);
    var technician = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(), "kpi-tech-" + UUID.randomUUID() + "@syncro.test", "hash",
        ApplicationRole.TECHNICIAN, true, T0, T0));
    var breakdown = categories.saveAndFlush(new com.syncro.maintenance.infrastructure.db
        .WorkOrderCategoryEntity(UUID.randomUUID(), "01", "Breakdown", null, T0, T0));
    var criterion = ratingCriteria.saveAndFlush(
        new com.syncro.maintenance.infrastructure.db.WorkLogRatingCriterionEntity(
            UUID.randomUUID(), "Quality", null, 1, 5, plant.getId(), true, 0, null, T0, T0));

    // Two CLOSED breakdown WOs on one machine, stopped Aug 10 and Aug 20 (woStopAt via
    // PENDING_REVIEW history) → MTBF gap 10 days; WO-1 has two work logs (60+30 wall
    // minutes), WO-2 one (60) → MTTR wall mean 75; ratings 4 and 5 → avg 4.50;
    // first-time-fix 1/2 → 50%. A third CLOSED WO (KPI-WO-3) has NO PENDING_REVIEW
    // history — its stop derives from updatedAt (Aug 31 08:00, the sync edge case the
    // query comment calls out) → gaps 10.00 + 10.8333 days → MTBF 10.42, count 3.
    var wo1 = workOrders.saveAndFlush(new com.syncro.maintenance.infrastructure.db.WorkOrderEntity(
        "KPI-WO-1", "INTERNAL", null,
        com.syncro.maintenance.domain.workorder.WorkOrderStatus.CLOSED, breakdown.getId(),
        machine.getId(), "stop 1", 0L, null, technician.getId(), null, T0, T0));
    var wo2 = workOrders.saveAndFlush(new com.syncro.maintenance.infrastructure.db.WorkOrderEntity(
        "KPI-WO-2", "INTERNAL", null,
        com.syncro.maintenance.domain.workorder.WorkOrderStatus.CLOSED, breakdown.getId(),
        machine.getId(), "stop 2", 0L, null, technician.getId(), null, T0, T0));
    workOrders.saveAndFlush(new com.syncro.maintenance.infrastructure.db.WorkOrderEntity(
        "KPI-WO-3", "EXTERNAL", null,
        com.syncro.maintenance.domain.workorder.WorkOrderStatus.CLOSED, breakdown.getId(),
        machine.getId(), "sync-closed stop, no history", 0L, null, technician.getId(), null,
        T0, Instant.parse("2026-08-31T08:00:00Z")));
    statusHistory.saveAndFlush(new com.syncro.maintenance.infrastructure.db
        .WorkOrderStatusHistoryEntity(UUID.randomUUID(), wo1.getId(), null, "PENDING_REVIEW",
        "MANUAL", technician.getId().toString(), "trace-1",
        Instant.parse("2026-08-10T12:00:00Z")));
    statusHistory.saveAndFlush(new com.syncro.maintenance.infrastructure.db
        .WorkOrderStatusHistoryEntity(UUID.randomUUID(), wo2.getId(), null, "PENDING_REVIEW",
        "MANUAL", technician.getId().toString(), "trace-2",
        Instant.parse("2026-08-20T12:00:00Z")));
    var log1 = workLogs.saveAndFlush(new com.syncro.maintenance.infrastructure.db.WorkLogEntity(
        UUID.randomUUID(), null, wo1.getId(), technician.getId(),
        Instant.parse("2026-08-10T08:00:00Z"), Instant.parse("2026-08-10T09:00:00Z"),
        com.syncro.maintenance.domain.workorder.WorkLogStoppedReason.COMPLETED, "fix", null, null,
        T0, T0));
    workLogs.saveAndFlush(new com.syncro.maintenance.infrastructure.db.WorkLogEntity(
        UUID.randomUUID(), null, wo1.getId(), technician.getId(),
        Instant.parse("2026-08-10T10:00:00Z"), Instant.parse("2026-08-10T10:30:00Z"),
        com.syncro.maintenance.domain.workorder.WorkLogStoppedReason.COMPLETED, "fix 2", null, null,
        T0, T0));
    workLogs.saveAndFlush(new com.syncro.maintenance.infrastructure.db.WorkLogEntity(
        UUID.randomUUID(), null, wo2.getId(), technician.getId(),
        Instant.parse("2026-08-20T08:00:00Z"), Instant.parse("2026-08-20T09:00:00Z"),
        com.syncro.maintenance.domain.workorder.WorkLogStoppedReason.COMPLETED, "fix", null, null,
        T0, T0));
    workLogRatings.saveAndFlush(new com.syncro.maintenance.infrastructure.db.WorkLogRatingEntity(
        UUID.randomUUID(), log1.getId(), criterion.getId(), 4, technician.getId(), T0, null));
    workLogRatings.saveAndFlush(new com.syncro.maintenance.infrastructure.db.WorkLogRatingEntity(
        UUID.randomUUID(), workLogs.findByWorkOrderIdOrderByStartTimeAsc(wo2.getId()).get(0).getId(),
        criterion.getId(), 5, technician.getId(), T0, null));

    // PM: two planned workorders in August, one completed execution.
    var pmWo1 = pmWorkOrders.saveAndFlush(
        new com.syncro.maintenance.preventive.infrastructure.db.PmWorkOrderEntity(
            UUID.randomUUID(), machine.getId(), null, null, null, null, null,
            com.syncro.maintenance.preventive.domain.PmWorkOrderStatus.COMPLETED,
            technician.getId(), LocalDate.of(2026, 8, 5),
            Instant.parse("2026-08-06T01:00:00Z"), Instant.parse("2026-08-06T02:00:00Z"), null,
            T0, T0));
    pmWorkOrders.saveAndFlush(new com.syncro.maintenance.preventive.infrastructure.db
        .PmWorkOrderEntity(UUID.randomUUID(), machine.getId(), null, null, null, null, null,
        com.syncro.maintenance.preventive.domain.PmWorkOrderStatus.SCHEDULED, technician.getId(),
        LocalDate.of(2026, 8, 15), null, null, null, T0, T0));
    pmExecutions.saveAndFlush(new com.syncro.maintenance.preventive.infrastructure.db
        .PmExecutionEntity(UUID.randomUUID(), pmWo1.getId(), null, technician.getId(), null, null,
        null, null, null, Instant.parse("2026-08-06T01:00:00Z"),
        Instant.parse("2026-08-06T02:00:00Z"), false, 0, null, T0, T0));

    var outcomes = materialization.refreshMonth(AUG);
    assertThat(outcomes).allSatisfy(o -> assertThat(o.status())
        .isEqualTo(KpiAggregateRefreshStatus.SUCCESS));

    // MTBF: stops ordered by woStopAt (Aug10, Aug20, Aug31-08:00 via updatedAt fallback —
    // no PENDING_REVIEW history) → gaps 10.00 + 10.8333 days → mean 10.42.
    var mtbf = mtbfMonthlies.findByMachineIdAndMonth(machine.getId(), AUG).orElseThrow();
    assertThat(mtbf.getMtbfDays()).isEqualByComparingTo(new BigDecimal("10.42"));

    // MTTR: cumulative per WO (90 + 60) / 2 = 75.00 wall. v1 whole-working-day calendar
    // would give 1440 per log, but review 20-1 clamps working <= wall per log → 75.00.
    var mttr = mttrMonthlies.findByPlantIdAndMonth(plant.getId(), AUG).orElseThrow();
    assertThat(mttr.getWallClockMttrMinutes()).isEqualByComparingTo(new BigDecimal("75.00"));
    assertThat(mttr.getActualWorkingMttrMinutes()).isEqualByComparingTo(new BigDecimal("75.00"));

    // MAR: no telemetry availability source → explicit INSUFFICIENT_DATA, never zero.
    var mar = marMonthlies.findByPlantIdAndMonth(plant.getId(), AUG).orElseThrow();
    assertThat(mar.getMarPercent()).isNull();
    assertThat(mar.getSourceStatus()).isEqualTo(KpiSourceStatus.INSUFFICIENT_DATA.name());

    // PM completion: 1/2 = 50.00%.
    var pm = pmCompletionMonthlies.findByPlantIdAndMonth(plant.getId(), AUG).orElseThrow();
    assertThat(pm.getCompletionRate()).isEqualByComparingTo(new BigDecimal("50.00"));
    assertThat(pm.getCompletedCount()).isEqualTo(1);
    assertThat(pm.getPlannedCount()).isEqualTo(2);
    assertThat(pm.getSourceStatus()).isEqualTo(KpiSourceStatus.COMPLETE.name());

    // Technician: avg 4.50, total_wo 2, FTF 50.00.
    var tech = technicianMonthlies
        .findByPlantIdAndTechnicianIdAndMonth(plant.getId(), technician.getId(), AUG).orElseThrow();
    assertThat(tech.getAverageRating()).isEqualByComparingTo(new BigDecimal("4.50"));
    assertThat(tech.getTotalWo()).isEqualTo(2);
    assertThat(tech.getFirstTimeFixRate()).isEqualByComparingTo(new BigDecimal("50.00"));

    // Breakdown count: 3 stops in August (the third via the updatedAt fallback).
    assertThat(breakdowns.findByPlantIdAndMonth(plant.getId(), AUG).orElseThrow().getCount())
        .isEqualTo(3);

    // Refresh-log evidence: RUNNING→SUCCESS with traceId per type.
    var log = refreshLogs.findByRefreshKey("mtbf:2026-08").orElseThrow();
    assertThat(log.getStatus()).isEqualTo(KpiAggregateRefreshStatus.SUCCESS);
    assertThat(log.getMessage()).contains("traceId=");

    // Idempotent re-run: same row id, scoped to this test's machine (the shared reused
    // container may hold other tests' August rows — review 20-1).
    var mtbfId = mtbf.getId();
    var rerun = materialization.refreshType(KpiType.MTBF, AUG);
    assertThat(rerun.status()).isEqualTo(KpiAggregateRefreshStatus.SUCCESS);
    assertThat(mtbfMonthlies.findByMachineIdAndMonth(machine.getId(), AUG).orElseThrow().getId())
        .isEqualTo(mtbfId);
  }

  /** Machine with plant+group — MTBF rows need a real machines FK (V1). */
  private com.syncro.machine.infrastructure.MachineEntity machine(PlantEntity plant) {
    var group = machineGroups.saveAndFlush(new com.syncro.masterdata.infrastructure.MachineGroupEntity(
        UUID.randomUUID(), plant, "Group " + UUID.randomUUID().toString().substring(0, 6), T0, T0));
    return machines.saveAndFlush(new com.syncro.machine.infrastructure.MachineEntity(
        UUID.randomUUID(), plant, group, "KPI-" + UUID.randomUUID().toString().substring(0, 8),
        "KPI Machine", com.syncro.machine.domain.MachineStatus.ACTIVE, null, null, null, null,
        T0, T0));
  }
}
