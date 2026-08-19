package com.syncro.alert.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.sparepart.application.SparepartLifetimeEvaluator;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC);

  private SparepartAlertService service() {
    return new SparepartAlertService(alertRepository, auditLogWriter, installationRepository,
        machineRepository, clock);
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
    assertThat(saved.getThresholdPercentage()).isEqualTo(80);
    assertThat(saved.getStatus()).isEqualTo(SparepartAlertStatus.OPEN);
    assertThat(saved.getCurrentCounterSnapshot()).isEqualTo(900L);
    assertThat(saved.getConsumedProductionCountSnapshot()).isEqualTo(800L);
    assertThat(saved.getConsumedPercentageSnapshot()).isEqualByComparingTo(new BigDecimal("80.00"));
    assertThat(saved.getTraceId()).isEqualTo(traceId);

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
}
