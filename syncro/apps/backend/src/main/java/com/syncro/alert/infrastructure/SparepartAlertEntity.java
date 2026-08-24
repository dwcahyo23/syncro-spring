package com.syncro.alert.infrastructure;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.domain.SparepartAlertType;
import com.syncro.projection.application.CounterRateEstimator.CalculationBasis;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sparepart_alerts")
public class SparepartAlertEntity {

  @Id
  private UUID id;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Column(name = "machine_sparepart_installation_id", nullable = false)
  private UUID machineSparepartInstallationId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "machine_sparepart_installation_id", insertable = false, updatable = false)
  private MachineSparepartInstallationEntity installation;

  @Enumerated(EnumType.STRING)
  @Column(name = "alert_type", nullable = false, length = 24)
  private SparepartAlertType alertType;

  @Column(name = "threshold_percentage")
  private Integer thresholdPercentage;

  @Column(name = "current_counter_snapshot")
  private Long currentCounterSnapshot;

  @Column(name = "consumed_production_count_snapshot")
  private Long consumedProductionCountSnapshot;

  @Column(name = "consumed_percentage_snapshot", precision = 7, scale = 2)
  private BigDecimal consumedPercentageSnapshot;

  @Column(name = "lead_time_hours", precision = 12, scale = 2)
  private BigDecimal leadTimeHours;

  @Column(name = "rate_per_operating_hour", precision = 18, scale = 2)
  private BigDecimal ratePerOperatingHour;

  @Enumerated(EnumType.STRING)
  @Column(name = "calculation_basis", length = 24)
  private CalculationBasis calculationBasis;

  @Column(name = "projected_depletion_at")
  private Instant projectedDepletionAt;

  @Column(name = "trace_id", nullable = false, length = 64)
  private String traceId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private SparepartAlertStatus status;

  @Column(name = "status_reason", length = 255)
  private String statusReason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected SparepartAlertEntity() {
  }

  public SparepartAlertEntity(UUID id, UUID machineId, UUID machineSparepartInstallationId,
      SparepartAlertType alertType, Integer thresholdPercentage, Long currentCounterSnapshot,
      Long consumedProductionCountSnapshot, BigDecimal consumedPercentageSnapshot, String traceId,
      SparepartAlertStatus status, String statusReason, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.machineId = machineId;
    this.machineSparepartInstallationId = machineSparepartInstallationId;
    this.alertType = alertType;
    this.thresholdPercentage = thresholdPercentage;
    this.currentCounterSnapshot = currentCounterSnapshot;
    this.consumedProductionCountSnapshot = consumedProductionCountSnapshot;
    this.consumedPercentageSnapshot = consumedPercentageSnapshot;
    this.traceId = traceId;
    this.status = status;
    this.statusReason = statusReason;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() { return id; }
  public UUID getMachineId() { return machineId; }
  public UUID getMachineSparepartInstallationId() { return machineSparepartInstallationId; }
  public MachineSparepartInstallationEntity getInstallation() { return installation; }
  public SparepartAlertType getAlertType() { return alertType; }
  public Integer getThresholdPercentage() { return thresholdPercentage; }
  public Long getCurrentCounterSnapshot() { return currentCounterSnapshot; }
  public Long getConsumedProductionCountSnapshot() { return consumedProductionCountSnapshot; }
  public BigDecimal getConsumedPercentageSnapshot() { return consumedPercentageSnapshot; }
  public BigDecimal getLeadTimeHours() { return leadTimeHours; }
  public BigDecimal getRatePerOperatingHour() { return ratePerOperatingHour; }
  public CalculationBasis getCalculationBasis() { return calculationBasis; }
  public Instant getProjectedDepletionAt() { return projectedDepletionAt; }
  public String getTraceId() { return traceId; }
  public SparepartAlertStatus getStatus() { return status; }
  public String getStatusReason() { return statusReason; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public long getVersion() { return version; }

  /**
   * Snapshot the procurement-risk evidence at creation time (story 8-7). Only applicable for
   * {@link SparepartAlertType#PROCUREMENT_RISK} rows; evidence is never live-computed at read.
   */
  public void snapshotProcurementEvidence(BigDecimal leadTimeHours, BigDecimal ratePerOperatingHour,
      CalculationBasis calculationBasis, Instant projectedDepletionAt) {
    this.leadTimeHours = leadTimeHours;
    this.ratePerOperatingHour = ratePerOperatingHour;
    this.calculationBasis = calculationBasis;
    this.projectedDepletionAt = projectedDepletionAt;
  }

  /**
   * Transition OPEN → ACKNOWLEDGED.
   * Throws {@link InvalidAlertTransitionException} if status is not OPEN.
   */
  public void acknowledge(String reason, Instant now) {
    if (this.status != SparepartAlertStatus.OPEN) {
      throw new InvalidAlertTransitionException(this.status, SparepartAlertStatus.ACKNOWLEDGED);
    }
    this.status = SparepartAlertStatus.ACKNOWLEDGED;
    this.statusReason = reason;
    this.updatedAt = now;
  }

  /**
   * Transition ACKNOWLEDGED → RESOLVED.
   * Throws {@link InvalidAlertTransitionException} if status is not ACKNOWLEDGED.
   */
  public void resolve(String reason, Instant now) {
    if (this.status != SparepartAlertStatus.ACKNOWLEDGED) {
      throw new InvalidAlertTransitionException(this.status, SparepartAlertStatus.RESOLVED);
    }
    this.status = SparepartAlertStatus.RESOLVED;
    this.statusReason = reason;
    this.updatedAt = now;
  }

  /**
   * SUPER_ADMIN override: OPEN → RESOLVED directly, skipping ACKNOWLEDGED.
   * Throws {@link InvalidAlertTransitionException} if status is not OPEN.
   */
  public void resolveOverride(String reason, Instant now) {
    if (this.status != SparepartAlertStatus.OPEN) {
      throw new InvalidAlertTransitionException(this.status, SparepartAlertStatus.RESOLVED);
    }
    this.status = SparepartAlertStatus.RESOLVED;
    this.statusReason = reason;
    this.updatedAt = now;
  }

  public static class InvalidAlertTransitionException extends RuntimeException {
    private final SparepartAlertStatus from;
    private final SparepartAlertStatus to;

    public InvalidAlertTransitionException(SparepartAlertStatus from, SparepartAlertStatus to) {
      super("Invalid alert transition: " + from + " → " + to);
      this.from = from;
      this.to = to;
    }

    public SparepartAlertStatus getFrom() { return from; }
    public SparepartAlertStatus getTo() { return to; }
  }
}
