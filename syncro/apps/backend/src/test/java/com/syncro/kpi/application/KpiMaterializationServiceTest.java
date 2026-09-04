package com.syncro.kpi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.kpi.application.KpiSourceDataReader.BreakdownStop;
import com.syncro.kpi.application.KpiSourceDataReader.RepairLogInterval;
import com.syncro.kpi.application.KpiSourceDataReader.TechnicianLog;
import com.syncro.kpi.application.KpiSourceDataReader.TechnicianRating;
import com.syncro.kpi.domain.KpiAggregateRefreshStatus;
import com.syncro.kpi.domain.KpiSourceStatus;
import com.syncro.kpi.domain.KpiType;
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
import com.syncro.kpi.infrastructure.db.KpiTechnicianMonthlyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Story 20-1 unit tests for {@link KpiMaterializationService}: the spec's "Always"
 * formulas — MTBF ordered by woStopAt (never id), MTTR cumulative per workorder in
 * both variants, MAR/PM explicit source_status, technician ratings — plus refresh-log
 * gating and idempotent re-runs. Persistence is mocked here (pure calculation); the
 * real-database proof lives in KpiEntityConventionIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class KpiMaterializationServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final LocalDate AUG = LocalDate.of(2026, 8, 1);

  private final UUID plantId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID technicianId = UUID.randomUUID();

  @Mock
  private KpiSourceDataReader source;
  @Mock
  private KpiCalendarReader calendar;
  @Mock
  private KpiRefreshGate gate;
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
  private PlatformTransactionManager transactionManager;

  private KpiMaterializationService service;

  @BeforeEach
  void setUp() {
    service = new KpiMaterializationService(source, calendar, gate, breakdowns, mtbfMonthlies,
        mttrMonthlies, marMonthlies, technicianMonthlies, pmCompletionMonthlies, CLOCK,
        transactionManager);
  }

  @Test
  @DisplayName("20-1-MTBF-001 P0: gaps ordered by woStopAt, not id — out-of-order input still yields the stop-time mean")
  void mtbfOrdersByWoStopAt() {
    // Returned newest-first (the reference impl's id-ordering bug shape): 25th, 10th, 20th(Jul).
    when(source.findBreakdownStops(eq(Instant.EPOCH), any())).thenReturn(List.of(
        new BreakdownStop("WO-3", machineId, plantId, Instant.parse("2026-08-25T00:00:00Z")),
        new BreakdownStop("WO-2", machineId, plantId, Instant.parse("2026-08-10T00:00:00Z")),
        new BreakdownStop("WO-1", machineId, plantId, Instant.parse("2026-07-20T00:00:00Z"))));
    when(mtbfMonthlies.findByMachineIdAndMonth(machineId, AUG)).thenReturn(Optional.empty());
    when(mtbfMonthlies.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    when(gate.tryStart("mtbf:2026-08")).thenReturn(true);

    var outcome = service.refreshType(KpiType.MTBF, AUG);

    assertThat(outcome.status()).isEqualTo(KpiAggregateRefreshStatus.SUCCESS);
    // Sorted by stop time: Jul20→Aug10 = 21d, Aug10→Aug25 = 15d → mean 18.00 days.
    var captor = ArgumentCaptor.forClass(KpiMtbfMonthlyEntity.class);
    verify(mtbfMonthlies).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getMtbfDays()).isEqualByComparingTo(new BigDecimal("18.00"));
    assertThat(captor.getValue().getPlantId()).isEqualTo(plantId);
    assertThat(captor.getValue().getMachineId()).isEqualTo(machineId);
    verify(gate).finish(eq("mtbf:2026-08"), eq(KpiAggregateRefreshStatus.SUCCESS), any());
  }

  @Test
  @DisplayName("20-1-MTBF-002 P1: fewer than 2 stops → no row (explicit insufficient-data downstream)")
  void mtbfSingleStopWritesNoRow() {
    when(source.findBreakdownStops(eq(Instant.EPOCH), any())).thenReturn(List.of(
        new BreakdownStop("WO-1", machineId, plantId, Instant.parse("2026-08-10T00:00:00Z"))));
    when(gate.tryStart("mtbf:2026-08")).thenReturn(true);

    service.refreshType(KpiType.MTBF, AUG);

    verify(mtbfMonthlies, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("20-1-MTTR-001 P0: cumulative per-WO durations; wall-clock vs actual-working variants")
  void mttrCumulativeBothVariants() {
    when(source.findBreakdownStops(eq(Instant.EPOCH), any())).thenReturn(List.of(
        new BreakdownStop("WO-1", machineId, plantId, Instant.parse("2026-08-10T00:00:00Z"))));
    // Two logs on WO-1: 60 and 30 wall minutes; calendar says 40 and 20 working minutes.
    when(source.findClosedBreakdownRepairLogs()).thenReturn(List.of(
        new RepairLogInterval("WO-1", plantId, machineId,
            Instant.parse("2026-08-09T08:00:00Z"), Instant.parse("2026-08-09T09:00:00Z")),
        new RepairLogInterval("WO-1", plantId, machineId,
            Instant.parse("2026-08-09T10:00:00Z"), Instant.parse("2026-08-09T10:30:00Z"))));
    when(calendar.workingMinutes(eq(plantId), any(), any())).thenReturn(40L, 20L);
    when(source.findAllPlantIds()).thenReturn(List.of(plantId));
    when(mttrMonthlies.findByPlantIdAndMonth(plantId, AUG)).thenReturn(Optional.empty());
    when(mttrMonthlies.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    when(gate.tryStart("mttr:2026-08")).thenReturn(true);

    service.refreshType(KpiType.MTTR, AUG);

    var captor = ArgumentCaptor.forClass(KpiMttrMonthlyEntity.class);
    verify(mttrMonthlies).saveAndFlush(captor.capture());
    // Cumulative per WO: wall 90, working 60; mean over the single WO.
    assertThat(captor.getValue().getWallClockMttrMinutes()).isEqualByComparingTo(new BigDecimal("90.00"));
    assertThat(captor.getValue().getActualWorkingMttrMinutes()).isEqualByComparingTo(new BigDecimal("60.00"));
  }

  @Test
  @DisplayName("20-1-MTTR-002 P1: no work logs → NULL values, never a fabricated zero")
  void mttrNoLogsNullValues() {
    when(source.findBreakdownStops(eq(Instant.EPOCH), any())).thenReturn(List.of());
    when(source.findClosedBreakdownRepairLogs()).thenReturn(List.of());
    when(source.findAllPlantIds()).thenReturn(List.of(plantId));
    when(mttrMonthlies.findByPlantIdAndMonth(plantId, AUG)).thenReturn(Optional.empty());
    when(mttrMonthlies.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    when(gate.tryStart("mttr:2026-08")).thenReturn(true);

    service.refreshType(KpiType.MTTR, AUG);

    var captor = ArgumentCaptor.forClass(KpiMttrMonthlyEntity.class);
    verify(mttrMonthlies).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getWallClockMttrMinutes()).isNull();
    assertThat(captor.getValue().getActualWorkingMttrMinutes()).isNull();
  }

  @Test
  @DisplayName("20-1-MAR-001 P0: telemetry gap → INSUFFICIENT_DATA source_status, mar NULL")
  void marInsufficientData() {
    when(source.findAllPlantIds()).thenReturn(List.of(plantId));
    when(source.findMarInputs(eq(plantId), any(), any())).thenReturn(Optional.empty());
    when(marMonthlies.findByPlantIdAndMonth(plantId, AUG)).thenReturn(Optional.empty());
    when(marMonthlies.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    when(gate.tryStart("mar:2026-08")).thenReturn(true);

    service.refreshType(KpiType.MAR, AUG);

    var captor = ArgumentCaptor.forClass(KpiMarMonthlyEntity.class);
    verify(marMonthlies).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getMarPercent()).isNull();
    assertThat(captor.getValue().getSourceStatus()).isEqualTo(KpiSourceStatus.INSUFFICIENT_DATA.name());
    assertThat(captor.getValue().getSourceMessage()).isNotBlank();
  }

  @Test
  @DisplayName("20-1-MAR-002 P0: planned/downtime minutes → MAR percent formula")
  void marComputed() {
    when(source.findAllPlantIds()).thenReturn(List.of(plantId));
    when(source.findMarInputs(eq(plantId), any(), any())).thenReturn(
        Optional.of(new KpiSourceDataReader.MarInputs(600L, 45L)));
    when(marMonthlies.findByPlantIdAndMonth(plantId, AUG)).thenReturn(Optional.empty());
    when(marMonthlies.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    when(gate.tryStart("mar:2026-08")).thenReturn(true);

    service.refreshType(KpiType.MAR, AUG);

    var captor = ArgumentCaptor.forClass(KpiMarMonthlyEntity.class);
    verify(marMonthlies).saveAndFlush(captor.capture());
    // (600 - 45) / 600 × 100 = 92.50
    assertThat(captor.getValue().getMarPercent()).isEqualByComparingTo(new BigDecimal("92.50"));
    assertThat(captor.getValue().getSourceStatus()).isEqualTo(KpiSourceStatus.COMPLETE.name());
  }

  @Test
  @DisplayName("20-1-PM-001 P1: no planned PM workorders → INSUFFICIENT_DATA")
  void pmCompletionInsufficient() {
    when(source.findAllPlantIds()).thenReturn(List.of(plantId));
    when(source.countPmPlanned(eq(plantId), any(), any())).thenReturn(0L);
    when(source.countPmCompleted(eq(plantId), any(), any())).thenReturn(0L);
    when(pmCompletionMonthlies.findByPlantIdAndMonth(plantId, AUG)).thenReturn(Optional.empty());
    when(pmCompletionMonthlies.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    when(gate.tryStart("pm-completion:2026-08")).thenReturn(true);

    service.refreshType(KpiType.PM_COMPLETION, AUG);

    var captor = ArgumentCaptor.forClass(KpiPmCompletionMonthlyEntity.class);
    verify(pmCompletionMonthlies).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getCompletionRate()).isNull();
    assertThat(captor.getValue().getSourceStatus()).isEqualTo(KpiSourceStatus.INSUFFICIENT_DATA.name());
  }

  @Test
  @DisplayName("20-1-TECH-001 P0: average rating, distinct WO count, first-time-fix rate")
  void technicianKpis() {
    // WO-1 has two logs (not first-time-fix), WO-2 has one (first-time-fix).
    when(source.findTechnicianLogs(any(), any())).thenReturn(List.of(
        new TechnicianLog(technicianId, plantId, "WO-1"),
        new TechnicianLog(technicianId, plantId, "WO-1"),
        new TechnicianLog(technicianId, plantId, "WO-2")));
    when(source.findTechnicianRatings(any(), any())).thenReturn(List.of(
        new TechnicianRating(technicianId, plantId, "WO-1", 4),
        new TechnicianRating(technicianId, plantId, "WO-2", 5)));
    when(technicianMonthlies.findByPlantIdAndTechnicianIdAndMonth(plantId, technicianId, AUG))
        .thenReturn(Optional.empty());
    when(technicianMonthlies.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    when(gate.tryStart("technician:2026-08")).thenReturn(true);

    service.refreshType(KpiType.TECHNICIAN, AUG);

    var captor = ArgumentCaptor.forClass(KpiTechnicianMonthlyEntity.class);
    verify(technicianMonthlies).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getAverageRating()).isEqualByComparingTo(new BigDecimal("4.50"));
    assertThat(captor.getValue().getTotalWo()).isEqualTo(2);
    assertThat(captor.getValue().getFirstTimeFixRate()).isEqualByComparingTo(new BigDecimal("50.00"));
  }

  @Test
  @DisplayName("20-1-IDEM-001 P0: re-run upserts the same row (id preserved) and logs SUCCESS again")
  void idempotentReRunUpsertsSameRow() {
    var existingId = UUID.randomUUID();
    var existing = new KpiMtbfMonthlyEntity(existingId, plantId, machineId, AUG,
        new BigDecimal("9.00"), NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    when(source.findBreakdownStops(eq(Instant.EPOCH), any())).thenReturn(List.of(
        new BreakdownStop("WO-1", machineId, plantId, Instant.parse("2026-07-20T00:00:00Z")),
        new BreakdownStop("WO-2", machineId, plantId, Instant.parse("2026-08-10T00:00:00Z"))));
    when(mtbfMonthlies.findByMachineIdAndMonth(machineId, AUG)).thenReturn(Optional.of(existing));
    when(mtbfMonthlies.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    when(gate.tryStart("mtbf:2026-08")).thenReturn(true);

    service.refreshType(KpiType.MTBF, AUG);

    var captor = ArgumentCaptor.forClass(KpiMtbfMonthlyEntity.class);
    verify(mtbfMonthlies).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getId()).isEqualTo(existingId);
    assertThat(captor.getValue().getMtbfDays()).isEqualByComparingTo(new BigDecimal("21.00"));
    assertThat(captor.getValue().getCreatedAt()).isEqualTo(existing.getCreatedAt());
  }

  @Test
  @DisplayName("20-1-GATE-001 P0: RUNNING key blocks a concurrent pass — no compute, no finish")
  void runningKeyBlocksConcurrentPass() {
    when(gate.tryStart("mtbf:2026-08")).thenReturn(false);

    var outcome = service.refreshType(KpiType.MTBF, AUG);

    assertThat(outcome.status()).isEqualTo(KpiAggregateRefreshStatus.RUNNING);
    verify(mtbfMonthlies, never()).saveAndFlush(any());
    verify(gate, never()).finish(eq("mtbf:2026-08"), any(), any());
  }

  @Test
  @DisplayName("20-1-GATE-002 P1: compute failure → FAILED with traceId evidence, RUNNING→FAILED transition logged")
  void computeFailureLogsFailed() {
    when(gate.tryStart("breakdown:2026-08")).thenReturn(true);
    when(source.findBreakdownStops(any(), any())).thenThrow(new IllegalStateException("db down"));

    var outcome = service.refreshType(KpiType.BREAKDOWN, AUG);

    assertThat(outcome.status()).isEqualTo(KpiAggregateRefreshStatus.FAILED);
    // Review 20-1: the log message is API-exposed — only the exception class name is
    // stored; the raw detail stays in the server log.
    assertThat(outcome.message()).contains("traceId=").contains("IllegalStateException")
        .doesNotContain("db down");
    verify(gate).finish(eq("breakdown:2026-08"), eq(KpiAggregateRefreshStatus.FAILED), any());
    verify(breakdowns, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("20-1-BD-001 P1: breakdown count per plant for the month")
  void breakdownCountPerPlant() {
    when(source.findBreakdownStops(any(), any())).thenReturn(List.of(
        new BreakdownStop("WO-1", machineId, plantId, Instant.parse("2026-08-05T00:00:00Z")),
        new BreakdownStop("WO-2", machineId, plantId, Instant.parse("2026-08-20T00:00:00Z"))));
    when(source.findAllPlantIds()).thenReturn(List.of(plantId));
    when(breakdowns.findByPlantIdAndMonth(plantId, AUG)).thenReturn(Optional.empty());
    when(breakdowns.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    when(gate.tryStart("breakdown:2026-08")).thenReturn(true);

    service.refreshType(KpiType.BREAKDOWN, AUG);

    var captor = ArgumentCaptor.forClass(KpiMonthlyBreakdownEntity.class);
    verify(breakdowns).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getCount()).isEqualTo(2);
  }
}
