package com.syncro.alert.application;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.domain.SparepartAlertType;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.notification.domain.AlertOpenedEvent;
import com.syncro.projection.application.SparepartProjectionService;
import com.syncro.sparepart.application.SparepartLifetimeEvaluator;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SparepartAlertService {

  private static final Logger log = LoggerFactory.getLogger(SparepartAlertService.class);

  private final SparepartAlertRepository alertRepository;
  private final AuditLogWriter auditLogWriter;
  private final MachineSparepartInstallationRepository installationRepository;
  private final MachineRepository machineRepository;
  private final SparepartProjectionService projectionService;
  private final SparepartProcurementRiskAlertCreator procurementRiskCreator;
  private final Clock clock;
  private final ApplicationEventPublisher eventPublisher;

  public SparepartAlertService(SparepartAlertRepository alertRepository,
      AuditLogWriter auditLogWriter,
      MachineSparepartInstallationRepository installationRepository,
      MachineRepository machineRepository,
      SparepartProjectionService projectionService,
      SparepartProcurementRiskAlertCreator procurementRiskCreator,
      Clock clock,
      ApplicationEventPublisher eventPublisher) {
    this.alertRepository = alertRepository;
    this.auditLogWriter = auditLogWriter;
    this.installationRepository = installationRepository;
    this.machineRepository = machineRepository;
    this.projectionService = projectionService;
    this.procurementRiskCreator = procurementRiskCreator;
    this.clock = clock;
    this.eventPublisher = eventPublisher;
  }

  @Transactional
  public void evaluateAndCreateAlerts(UUID machineId,
      Map<UUID, SparepartLifetimeEvaluator.EvaluationResult> results, String traceId) {
    if (results.isEmpty()) {
      return;
    }

    var machine = machineRepository.findByIdWithPlantAndGroup(machineId).orElse(null);
    if (machine == null) {
      log.warn("[traceId={}] Machine {} not found during alert evaluation, skipping", traceId, machineId);
      return;
    }
    if (machine.getPlant() == null) {
      log.warn("[traceId={}] Machine {} has no plant during alert evaluation, skipping", traceId, machineId);
      return;
    }
    UUID plantId = machine.getPlant().getId();

    for (var entry : results.entrySet()) {
      UUID installationId = entry.getKey();
      SparepartLifetimeEvaluator.EvaluationResult result = entry.getValue();

      var installation = installationRepository.findById(installationId).orElse(null);
      if (installation == null) {
        log.warn("[traceId={}] Installation {} not found during alert evaluation, skipping",
            traceId, installationId);
        continue;
      }

      int thresholdPercentage = installation.getThresholdPercentage();

      if (result.consumedPercentage().compareTo(BigDecimal.valueOf(thresholdPercentage)) < 0) {
        continue;
      }

      boolean dedupExists = alertRepository
          .existsByMachineSparepartInstallationIdAndThresholdPercentageAndStatusNot(
              installationId, thresholdPercentage, SparepartAlertStatus.RESOLVED);
      if (dedupExists) {
        log.debug("[traceId={}] Non-RESOLVED alert already exists for installation {} threshold {}%, skipping",
            traceId, installationId, thresholdPercentage);
        continue;
      }

      Instant now = Instant.now(clock);
      UUID alertId = UUID.randomUUID();
      var alert = new SparepartAlertEntity(
          alertId,
          machineId,
          installationId,
          SparepartAlertType.THRESHOLD_PERCENTAGE,
          thresholdPercentage,
          result.currentCount(),
          result.consumedProductionCount(),
          result.consumedPercentage(),
          traceId,
          SparepartAlertStatus.OPEN,
          null,
          now,
          now);
      try {
        alertRepository.save(alert);
      } catch (DataIntegrityViolationException e) {
        // Concurrent evaluation created the same alert — treat as idempotent skip
        log.debug("[traceId={}] Concurrent alert creation for installation {} threshold {}%, skipping",
            traceId, installationId, thresholdPercentage);
        continue;
      }

      eventPublisher.publishEvent(new AlertOpenedEvent(alertId, machineId, traceId));

      auditLogWriter.recordSystem(new AuditRecord(
          AuditAction.CREATE,
          AuditEntityType.ALERT,
          alertId,
          "ALERT:" + installationId + "@" + thresholdPercentage + "%",
          plantId,
          null,
          Map.of(
              "machineId", machineId.toString(),
              "installationId", installationId.toString(),
              "thresholdPercentage", thresholdPercentage,
              "consumedPercentage", result.consumedPercentage().toPlainString(),
              "traceId", traceId)));

      log.info("[traceId={}] Alert created for installation {} threshold {}% consumed {}%",
          traceId, installationId, thresholdPercentage, result.consumedPercentage());
    }
  }

  /**
   * Non-transactional orchestrator for procurement-risk evaluation (story 8-7). Resolves the
   * machine, short-circuits on a cheap lead-time existence check, reuses the projection module's
   * cached-or-computed view, filters installations whose projected depletion falls inside their
   * lead-time window, and delegates per-installation creation to {@link SparepartProcurementRiskAlertCreator}.
   *
   * <p>Every skip is silent by design: missing machine/plant, no lead-time sparepart, no rate, or
   * depletion outside the window never raise, never log a failure, and never guess a value.
   */
  public void evaluateAndCreateProcurementRiskAlerts(UUID machineId, String traceId) {
    var machine = machineRepository.findByIdWithPlantAndGroup(machineId).orElse(null);
    if (machine == null) {
      log.warn("[traceId={}] Machine {} not found during procurement-risk evaluation, skipping",
          traceId, machineId);
      return;
    }
    if (machine.getPlant() == null) {
      log.warn("[traceId={}] Machine {} has no plant during procurement-risk evaluation, skipping",
          traceId, machineId);
      return;
    }

    if (!installationRepository.existsByMachineIdAndSparepartLeadTimeHoursIsNotNull(machineId)) {
      log.debug("[traceId={}] Machine {} has no lead-time spareparts, skipping procurement-risk evaluation",
          traceId, machineId);
      return;
    }

    var view = projectionService.getProjectionsForMachine(machineId);
    if (view == null || !view.rateAvailable()) {
      log.debug("[traceId={}] Machine {} projection rate unavailable, skipping procurement-risk evaluation",
          traceId, machineId);
      return;
    }

    for (var projection : view.projections()) {
      if (!(projection.available()
          && projection.leadTimeHours() != null
          && projection.consumptionDuringLeadTime() != null
          && projection.remainingCounters() != null
          && projection.projectedDepletionAt() != null
          && projection.consumptionDuringLeadTime() >= projection.remainingCounters())) {
        continue;
      }
      try {
        procurementRiskCreator.create(machineId, machine.getPlant().getId(),
            projection.installationId(), traceId, projection.leadTimeHours(),
            view.ratePerOperatingHour(), view.calculationBasis(), projection.projectedDepletionAt());
      } catch (SparepartProcurementRiskAlertCreator.DedupConflict e) {
        log.debug("[traceId={}] Non-RESOLVED PROCUREMENT_RISK alert already exists for installation {}, skipping",
            traceId, e.getInstallationId());
      }
    }
  }
}
