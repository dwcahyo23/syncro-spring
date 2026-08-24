package com.syncro.alert.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.domain.SparepartAlertType;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.notification.domain.AlertOpenedEvent;
import com.syncro.projection.application.SparepartProjectionService;
import com.syncro.sparepart.application.SparepartLifetimeEvaluator;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class SparepartAlertServiceTest {

  @Mock
  private SparepartAlertRepository alertRepository;

  @Mock
  private AuditLogWriter auditLogWriter;

  @Mock
  private MachineSparepartInstallationRepository installationRepository;

  @Mock
  private MachineRepository machineRepository;

  @Mock
  private SparepartProjectionService projectionService;

  @Mock
  private ApplicationEventPublisher eventPublisher;

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC);

  private SparepartAlertService service() {
    var creator = new SparepartProcurementRiskAlertCreator(
        alertRepository, auditLogWriter, clock, eventPublisher);
    return new SparepartAlertService(alertRepository, auditLogWriter, installationRepository,
        machineRepository, projectionService, creator, clock, eventPublisher);
  }

  // --- helpers ---

  private MachineEntity machineWithPlant(UUID machineId, UUID plantId) {
    var plant = new PlantEntity(plantId, "P1", "Plant 1", Instant.now(clock), Instant.now(clock));
    return new MachineEntity(machineId, plant, null, "M1", "Machine 1",
        com.syncro.machine.domain.MachineStatus.ACTIVE, null, null, null, null,
        Instant.now(clock), Instant.now(clock));
  }

  private MachineSparepartInstallationEntity installation(UUID installationId, UUID machineId,
      int thresholdPercentage) {
    // Use a partial mock stub — we only need id, machine.id, thresholdPercentage
    var machine = new MachineEntity(machineId, null, null, "M1", null,
        com.syncro.machine.domain.MachineStatus.ACTIVE, null, null, null, null,
        Instant.now(clock), Instant.now(clock));
    return new MachineSparepartInstallationEntity(
        installationId, machine, null, "func", 1000L, 0L, thresholdPercentage,
        Instant.now(clock), Instant.now(clock), Instant.now(clock));
  }

  private SparepartLifetimeEvaluator.EvaluationResult result(long current, long consumed,
      String pct) {
    return new SparepartLifetimeEvaluator.EvaluationResult(current, consumed,
        new BigDecimal(pct));
  }

  // --- tests ---

  @Test
  void thresholdReached_createsAlert() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();
    UUID installationId = UUID.randomUUID();
    String traceId = "trace-001";

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.findById(installationId))
        .thenReturn(Optional.of(installation(installationId, machineId, 80)));
    when(alertRepository.existsByMachineSparepartInstallationIdAndThresholdPercentageAndStatusNot(
        installationId, 80, SparepartAlertStatus.RESOLVED))
        .thenReturn(false);
    when(alertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().evaluateAndCreateAlerts(machineId,
        Map.of(installationId, result(900L, 800L, "80.00")), traceId);

    var captor = ArgumentCaptor.forClass(SparepartAlertEntity.class);
    verify(alertRepository).save(captor.capture());
    SparepartAlertEntity saved = captor.getValue();
    assertThat(saved.getMachineId()).isEqualTo(machineId);
    assertThat(saved.getMachineSparepartInstallationId()).isEqualTo(installationId);
    assertThat(saved.getAlertType()).isEqualTo(SparepartAlertType.THRESHOLD_PERCENTAGE);
    assertThat(saved.getThresholdPercentage()).isEqualTo(80);
    assertThat(saved.getStatus()).isEqualTo(SparepartAlertStatus.OPEN);
    assertThat(saved.getCurrentCounterSnapshot()).isEqualTo(900L);
    assertThat(saved.getConsumedProductionCountSnapshot()).isEqualTo(800L);
    assertThat(saved.getConsumedPercentageSnapshot()).isEqualByComparingTo(new BigDecimal("80.00"));
    assertThat(saved.getTraceId()).isEqualTo(traceId);
    assertThat(saved.getLeadTimeHours()).isNull();
    assertThat(saved.getRatePerOperatingHour()).isNull();
    assertThat(saved.getCalculationBasis()).isNull();
    assertThat(saved.getProjectedDepletionAt()).isNull();

    verify(auditLogWriter).recordSystem(any(AuditRecord.class));
  }

  @Test
  void dedupNonResolvedAlertExists_noNewAlertCreated() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();
    UUID installationId = UUID.randomUUID();

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.findById(installationId))
        .thenReturn(Optional.of(installation(installationId, machineId, 80)));
    when(alertRepository.existsByMachineSparepartInstallationIdAndThresholdPercentageAndStatusNot(
        installationId, 80, SparepartAlertStatus.RESOLVED))
        .thenReturn(true);

    service().evaluateAndCreateAlerts(machineId,
        Map.of(installationId, result(900L, 800L, "80.00")), "trace-002");

    verify(alertRepository, never()).save(any());
    verify(auditLogWriter, never()).recordSystem(any());
  }

  @Test
  void belowThreshold_noAlertCreated() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();
    UUID installationId = UUID.randomUUID();

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.findById(installationId))
        .thenReturn(Optional.of(installation(installationId, machineId, 80)));

    service().evaluateAndCreateAlerts(machineId,
        Map.of(installationId, result(700L, 600L, "60.00")), "trace-003");

    verify(alertRepository, never()).save(any());
    verify(auditLogWriter, never()).recordSystem(any());
  }

  @Test
  void resolvedAlertExistsAndThresholdReCrossed_newAlertCreated() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();
    UUID installationId = UUID.randomUUID();

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.findById(installationId))
        .thenReturn(Optional.of(installation(installationId, machineId, 80)));
    // existsBy... returns false because previous alert was RESOLVED (excluded)
    when(alertRepository.existsByMachineSparepartInstallationIdAndThresholdPercentageAndStatusNot(
        installationId, 80, SparepartAlertStatus.RESOLVED))
        .thenReturn(false);
    when(alertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().evaluateAndCreateAlerts(machineId,
        Map.of(installationId, result(950L, 850L, "85.00")), "trace-004");

    verify(alertRepository).save(any(SparepartAlertEntity.class));
    verify(auditLogWriter).recordSystem(any(AuditRecord.class));
  }

  @Test
  void emptyResultsMap_noOp() {
    UUID machineId = UUID.randomUUID();

    service().evaluateAndCreateAlerts(machineId, Map.of(), "trace-005");

    verify(machineRepository, never()).findByIdWithPlantAndGroup(any());
    verify(alertRepository, never()).save(any());
    verify(auditLogWriter, never()).recordSystem(any());
  }

  // --- procurement-risk evaluation (story 8-7) ---

  private com.syncro.projection.api.ProjectionDtos.InstallationProjection projection(
      UUID installationId, boolean available, BigDecimal leadTime, Long consumption, Long remaining) {
    return new com.syncro.projection.api.ProjectionDtos.InstallationProjection(
        installationId, UUID.randomUUID(), "func", available, null, remaining,
        Instant.parse("2026-08-25T00:00:00Z"), leadTime, consumption);
  }

  private com.syncro.projection.api.ProjectionDtos.MachineSparepartProjectionsView riskView(
      UUID machineId, List<com.syncro.projection.api.ProjectionDtos.InstallationProjection> rows) {
    return new com.syncro.projection.api.ProjectionDtos.MachineSparepartProjectionsView(
        machineId, true, com.syncro.projection.application.CounterRateEstimator.CalculationBasis.ROLLING_30_DAY,
        null, null, null, null, null, new BigDecimal("15.00"), "MACHINE",
        new BigDecimal("8.00"), rows);
  }

  @Test
  void procurementRisk_depletionWithinWindow_createsAlertWithEvidence() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();
    UUID installationId = UUID.randomUUID();
    String traceId = "trace-risk-001";

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(machineId))
        .thenReturn(true);
    var view = riskView(machineId, List.of(
        projection(installationId, true, new BigDecimal("36.5"), 548L, 500L)));
    when(projectionService.getProjectionsForMachine(machineId)).thenReturn(view);
    when(alertRepository.existsByMachineSparepartInstallationIdAndAlertTypeAndStatusNot(
        installationId, SparepartAlertType.PROCUREMENT_RISK, SparepartAlertStatus.RESOLVED))
        .thenReturn(false);
    when(alertRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service().evaluateAndCreateProcurementRiskAlerts(machineId, traceId);

    var captor = ArgumentCaptor.forClass(SparepartAlertEntity.class);
    verify(alertRepository).saveAndFlush(captor.capture());
    SparepartAlertEntity saved = captor.getValue();
    assertThat(saved.getMachineId()).isEqualTo(machineId);
    assertThat(saved.getMachineSparepartInstallationId()).isEqualTo(installationId);
    assertThat(saved.getAlertType()).isEqualTo(SparepartAlertType.PROCUREMENT_RISK);
    assertThat(saved.getThresholdPercentage()).isNull();
    assertThat(saved.getCurrentCounterSnapshot()).isNull();
    assertThat(saved.getConsumedProductionCountSnapshot()).isNull();
    assertThat(saved.getConsumedPercentageSnapshot()).isNull();
    assertThat(saved.getStatus()).isEqualTo(SparepartAlertStatus.OPEN);
    assertThat(saved.getLeadTimeHours()).isEqualByComparingTo(new BigDecimal("36.5"));
    assertThat(saved.getRatePerOperatingHour()).isEqualByComparingTo(new BigDecimal("15.00"));
    assertThat(saved.getCalculationBasis())
        .isEqualTo(com.syncro.projection.application.CounterRateEstimator.CalculationBasis.ROLLING_30_DAY);
    assertThat(saved.getProjectedDepletionAt()).isEqualTo(Instant.parse("2026-08-25T00:00:00Z"));
    assertThat(saved.getTraceId()).isEqualTo(traceId);

    verify(eventPublisher).publishEvent(any(AlertOpenedEvent.class));
    verify(auditLogWriter).recordSystem(any(AuditRecord.class));
  }

  @Test
  void procurementRisk_equalityBoundary_createsAlert() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();
    UUID installationId = UUID.randomUUID();

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(machineId))
        .thenReturn(true);
    var view = riskView(machineId, List.of(
        projection(installationId, true, new BigDecimal("36.5"), 500L, 500L)));
    when(projectionService.getProjectionsForMachine(machineId)).thenReturn(view);
    when(alertRepository.existsByMachineSparepartInstallationIdAndAlertTypeAndStatusNot(
        installationId, SparepartAlertType.PROCUREMENT_RISK, SparepartAlertStatus.RESOLVED))
        .thenReturn(false);
    when(alertRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service().evaluateAndCreateProcurementRiskAlerts(machineId, "trace-risk-eq");

    verify(alertRepository).saveAndFlush(any(SparepartAlertEntity.class));
  }

  @Test
  void procurementRisk_dedupeNonResolved_skips() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();
    UUID installationId = UUID.randomUUID();

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(machineId))
        .thenReturn(true);
    var view = riskView(machineId, List.of(
        projection(installationId, true, new BigDecimal("36.5"), 548L, 500L)));
    when(projectionService.getProjectionsForMachine(machineId)).thenReturn(view);
    when(alertRepository.existsByMachineSparepartInstallationIdAndAlertTypeAndStatusNot(
        installationId, SparepartAlertType.PROCUREMENT_RISK, SparepartAlertStatus.RESOLVED))
        .thenReturn(true);

    service().evaluateAndCreateProcurementRiskAlerts(machineId, "trace-risk-dup");

    verify(alertRepository, never()).saveAndFlush(any());
    verify(auditLogWriter, never()).recordSystem(any());
  }

  @Test
  void procurementRisk_missingLeadTime_silentNoOp() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();
    UUID installationId = UUID.randomUUID();

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(machineId))
        .thenReturn(true);
    var view = riskView(machineId, List.of(
        projection(installationId, true, null, null, 500L)));
    when(projectionService.getProjectionsForMachine(machineId)).thenReturn(view);

    service().evaluateAndCreateProcurementRiskAlerts(machineId, "trace-risk-nolt");

    verify(alertRepository, never()).saveAndFlush(any());
    verify(auditLogWriter, never()).recordSystem(any());
  }

  @Test
  void procurementRisk_outsideWindow_silentNoOp() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();
    UUID installationId = UUID.randomUUID();

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(machineId))
        .thenReturn(true);
    var view = riskView(machineId, List.of(
        projection(installationId, true, new BigDecimal("36.5"), 400L, 500L)));
    when(projectionService.getProjectionsForMachine(machineId)).thenReturn(view);

    service().evaluateAndCreateProcurementRiskAlerts(machineId, "trace-risk-out");

    verify(alertRepository, never()).saveAndFlush(any());
    verify(auditLogWriter, never()).recordSystem(any());
  }

  @Test
  void procurementRisk_rateUnavailable_silentNoOp() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(machineId))
        .thenReturn(true);
    when(projectionService.getProjectionsForMachine(machineId)).thenReturn(
        new com.syncro.projection.api.ProjectionDtos.MachineSparepartProjectionsView(
            machineId, false, null, null, null, null, null,
            com.syncro.projection.application.CounterRateEstimator.InsufficientReason.NO_TELEMETRY,
            null, "NONE", BigDecimal.ZERO.setScale(2), List.of()));

    service().evaluateAndCreateProcurementRiskAlerts(machineId, "trace-risk-norate");

    verify(alertRepository, never()).saveAndFlush(any());
    verify(auditLogWriter, never()).recordSystem(any());
  }

  @Test
  void procurementRisk_viewNull_silentNoOp() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(machineId))
        .thenReturn(true);
    when(projectionService.getProjectionsForMachine(machineId)).thenReturn(null);

    service().evaluateAndCreateProcurementRiskAlerts(machineId, "trace-risk-nullview");

    verify(alertRepository, never()).saveAndFlush(any());
    verify(auditLogWriter, never()).recordSystem(any());
  }

  @Test
  void procurementRisk_noLeadTimeSpareparts_perfGuardShortCircuits() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(machineId))
        .thenReturn(false);

    service().evaluateAndCreateProcurementRiskAlerts(machineId, "trace-risk-guard");

    verify(projectionService, never()).getProjectionsForMachine(any());
    verify(alertRepository, never()).saveAndFlush(any());
    verify(auditLogWriter, never()).recordSystem(any());
  }

  @Test
  void procurementRisk_unknownMachine_warnSkip() {
    UUID machineId = UUID.randomUUID();
    when(machineRepository.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.empty());

    service().evaluateAndCreateProcurementRiskAlerts(machineId, "trace-risk-unknown");

    verify(installationRepository, never()).existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(any());
    verify(alertRepository, never()).saveAndFlush(any());
  }

  @Test
  void procurementRisk_depletedNow_createsAlert() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();
    UUID installationId = UUID.randomUUID();

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(machineId))
        .thenReturn(true);
    var view = riskView(machineId, List.of(
        projection(installationId, true, new BigDecimal("36.5"), 100L, 0L)));
    when(projectionService.getProjectionsForMachine(machineId)).thenReturn(view);
    when(alertRepository.existsByMachineSparepartInstallationIdAndAlertTypeAndStatusNot(
        installationId, SparepartAlertType.PROCUREMENT_RISK, SparepartAlertStatus.RESOLVED))
        .thenReturn(false);
    when(alertRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service().evaluateAndCreateProcurementRiskAlerts(machineId, "trace-risk-depleted");

    verify(alertRepository).saveAndFlush(any(SparepartAlertEntity.class));
    verify(auditLogWriter).recordSystem(any(AuditRecord.class));
  }

  @Test
  void procurementRisk_auditRecordsEvidenceAndLabel() {
    UUID machineId = UUID.randomUUID();
    UUID plantId = UUID.randomUUID();
    UUID installationId = UUID.randomUUID();

    when(machineRepository.findByIdWithPlantAndGroup(machineId))
        .thenReturn(Optional.of(machineWithPlant(machineId, plantId)));
    when(installationRepository.existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(machineId))
        .thenReturn(true);
    var view = riskView(machineId, List.of(
        projection(installationId, true, new BigDecimal("36.5"), 548L, 500L)));
    when(projectionService.getProjectionsForMachine(machineId)).thenReturn(view);
    when(alertRepository.existsByMachineSparepartInstallationIdAndAlertTypeAndStatusNot(
        installationId, SparepartAlertType.PROCUREMENT_RISK, SparepartAlertStatus.RESOLVED))
        .thenReturn(false);
    when(alertRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service().evaluateAndCreateProcurementRiskAlerts(machineId, "trace-risk-audit");

    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLogWriter).recordSystem(captor.capture());
    AuditRecord record = captor.getValue();
    assertThat(record.entityLabel()).isEqualTo("ALERT:" + installationId + "@PROCUREMENT_RISK");
    assertThat(record.newValue()).containsEntry("alertType", "PROCUREMENT_RISK");
    assertThat(record.newValue()).containsEntry("ratePerOperatingHour", "15.00");
    assertThat(record.newValue()).containsEntry("calculationBasis", "ROLLING_30_DAY");
    assertThat(record.newValue()).containsEntry("leadTimeHours", "36.5");
    assertThat(record.newValue()).containsEntry("traceId", "trace-risk-audit");
  }
}
