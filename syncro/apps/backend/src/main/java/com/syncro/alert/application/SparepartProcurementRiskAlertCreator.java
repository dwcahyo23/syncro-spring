package com.syncro.alert.application;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.domain.SparepartAlertType;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.notification.domain.AlertOpenedEvent;
import com.syncro.projection.application.CounterRateEstimator.CalculationBasis;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a single {@code PROCUREMENT_RISK} alert for one installation (story 8-7). Runs in its
 * own {@code REQUIRES_NEW} transaction so a concurrent duplicate on one installation can never
 * roll back alerts already created for sibling installations, and so the {@link AlertOpenedEvent}
 * is published inside a live transaction — required for the {@code AFTER_COMMIT} notification
 * listener to route it (a self-invoked {@code @Transactional} on the orchestrator would bypass the
 * Spring proxy and silently drop the event).
 */
@Service
public class SparepartProcurementRiskAlertCreator {

  /** Dedupe index from V41 — one non-RESOLVED PROCUREMENT_RISK alert per installation. */
  static final String DEDUPE_CONSTRAINT = "sparepart_alerts_proc_risk_dedup_idx";

  private static final Logger log = LoggerFactory.getLogger(SparepartProcurementRiskAlertCreator.class);

  private final SparepartAlertRepository alertRepository;
  private final AuditLogWriter auditLogWriter;
  private final Clock clock;
  private final ApplicationEventPublisher eventPublisher;

  public SparepartProcurementRiskAlertCreator(SparepartAlertRepository alertRepository,
      AuditLogWriter auditLogWriter, Clock clock, ApplicationEventPublisher eventPublisher) {
    this.alertRepository = alertRepository;
    this.auditLogWriter = auditLogWriter;
    this.clock = clock;
    this.eventPublisher = eventPublisher;
  }

  /**
   * Create one PROCUREMENT_RISK alert snapshotting the evidence, or throw {@link DedupConflict}
   * when a non-RESOLVED alert already exists for the installation.
   *
   * @throws DedupConflict when the unique dedupe constraint fired (concurrent or prior alert)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void create(UUID machineId, UUID plantId, UUID installationId, String traceId,
      BigDecimal leadTimeHours, BigDecimal ratePerOperatingHour, CalculationBasis calculationBasis,
      Instant projectedDepletionAt) {
    if (alertRepository.existsByMachineSparepartInstallationIdAndAlertTypeAndStatusNot(
        installationId, SparepartAlertType.PROCUREMENT_RISK, SparepartAlertStatus.RESOLVED)) {
      log.debug("[traceId={}] Non-RESOLVED PROCUREMENT_RISK alert already exists for installation {}, skipping",
          traceId, installationId);
      throw new DedupConflict(installationId);
    }

    Instant now = Instant.now(clock);
    UUID alertId = UUID.randomUUID();
    // Threshold snapshots are null for procurement-risk rows (no threshold contract); the
    // evidence lives in the dedicated columns via snapshotProcurementEvidence below.
    var alert = new SparepartAlertEntity(
        alertId,
        machineId,
        installationId,
        SparepartAlertType.PROCUREMENT_RISK,
        null,
        null,
        null,
        null,
        traceId,
        SparepartAlertStatus.OPEN,
        null,
        now,
        now);
    alert.snapshotProcurementEvidence(leadTimeHours, ratePerOperatingHour, calculationBasis, projectedDepletionAt);
    try {
      // saveAndFlush surfaces the unique-constraint race here, inside the try, instead of at an
      // unrelated later flush (e.g. the audit insert) where it would not be caught.
      alertRepository.saveAndFlush(alert);
    } catch (DataIntegrityViolationException e) {
      if (e.getMostSpecificCause() instanceof ConstraintViolationException cve
          && DEDUPE_CONSTRAINT.equals(cve.getConstraintName())) {
        log.debug("[traceId={}] Concurrent PROCUREMENT_RISK alert creation for installation {}, skipping",
            traceId, installationId);
        throw new DedupConflict(installationId);
      }
      throw e;
    }

    eventPublisher.publishEvent(new AlertOpenedEvent(alertId, machineId, traceId));

    auditLogWriter.recordSystem(new AuditRecord(
        AuditAction.CREATE,
        AuditEntityType.ALERT,
        alertId,
        "ALERT:" + installationId + "@PROCUREMENT_RISK",
        plantId,
        null,
        Map.of(
            "machineId", machineId.toString(),
            "installationId", installationId.toString(),
            "alertType", SparepartAlertType.PROCUREMENT_RISK.name(),
            "ratePerOperatingHour", ratePerOperatingHour.toPlainString(),
            "calculationBasis", calculationBasis.name(),
            "leadTimeHours", leadTimeHours.toPlainString(),
            "projectedDepletionAt", projectedDepletionAt.toString(),
            "traceId", traceId),
        null));

    log.info("[traceId={}] PROCUREMENT_RISK alert created for installation {} leadTime {}h rate {}/op-h",
        traceId, installationId, leadTimeHours, ratePerOperatingHour);
  }

  /** A non-RESOLVED PROCUREMENT_RISK alert already exists (or lost a concurrent race). */
  public static class DedupConflict extends RuntimeException {
    private final UUID installationId;

    public DedupConflict(UUID installationId) {
      super("PROCUREMENT_RISK alert already exists for installation " + installationId);
      this.installationId = installationId;
    }

    public UUID getInstallationId() {
      return installationId;
    }
  }
}
