package com.syncro.alert.infrastructure;

import com.syncro.alert.domain.SparepartAlertStatus;
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

  @Column(name = "threshold_percentage", nullable = false)
  private int thresholdPercentage;

  @Column(name = "current_counter_snapshot", nullable = false)
  private long currentCounterSnapshot;

  @Column(name = "consumed_production_count_snapshot", nullable = false)
  private long consumedProductionCountSnapshot;

  @Column(name = "consumed_percentage_snapshot", nullable = false, precision = 7, scale = 2)
  private BigDecimal consumedPercentageSnapshot;

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

  protected SparepartAlertEntity() {
  }

  public SparepartAlertEntity(UUID id, UUID machineId, UUID machineSparepartInstallationId,
      int thresholdPercentage, long currentCounterSnapshot, long consumedProductionCountSnapshot,
      BigDecimal consumedPercentageSnapshot, String traceId, SparepartAlertStatus status,
      String statusReason, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.machineId = machineId;
    this.machineSparepartInstallationId = machineSparepartInstallationId;
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
  public int getThresholdPercentage() { return thresholdPercentage; }
  public long getCurrentCounterSnapshot() { return currentCounterSnapshot; }
  public long getConsumedProductionCountSnapshot() { return consumedProductionCountSnapshot; }
  public BigDecimal getConsumedPercentageSnapshot() { return consumedPercentageSnapshot; }
  public String getTraceId() { return traceId; }
  public SparepartAlertStatus getStatus() { return status; }
  public String getStatusReason() { return statusReason; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
