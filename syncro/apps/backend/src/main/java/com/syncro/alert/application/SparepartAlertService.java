package com.syncro.alert.application;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.sparepart.application.SparepartLifetimeEvaluator;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
  private final Clock clock;

  public SparepartAlertService(SparepartAlertRepository alertRepository,
      AuditLogWriter auditLogWriter,
      MachineSparepartInstallationRepository installationRepository,
      MachineRepository machineRepository,
      Clock clock) {
    this.alertRepository = alertRepository;
    this.auditLogWriter = auditLogWriter;
    this.installationRepository = installationRepository;
    this.machineRepository = machineRepository;
    this.clock = clock;
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
}
