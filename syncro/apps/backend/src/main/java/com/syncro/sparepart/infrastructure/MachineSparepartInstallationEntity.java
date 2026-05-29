package com.syncro.sparepart.infrastructure;

import com.syncro.machine.infrastructure.MachineEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "machine_sparepart_installations")
public class MachineSparepartInstallationEntity {
  @Id
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "machine_id", nullable = false)
  private MachineEntity machine;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "sparepart_id", nullable = false)
  private SparepartEntity sparepart;

  @Column(name = "expected_production_count", nullable = false)
  private long expectedProductionCount;

  @Column(name = "baseline_counter", nullable = false)
  private long baselineCounter;

  @Column(name = "threshold_percentage", nullable = false)
  private int thresholdPercentage;

  @Column(name = "installed_at", nullable = false)
  private Instant installedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MachineSparepartInstallationEntity() {
  }

  public MachineSparepartInstallationEntity(UUID id, MachineEntity machine, SparepartEntity sparepart,
      long expectedProductionCount, long baselineCounter, int thresholdPercentage, Instant installedAt,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.machine = machine;
    this.sparepart = sparepart;
    this.expectedProductionCount = expectedProductionCount;
    this.baselineCounter = baselineCounter;
    this.thresholdPercentage = thresholdPercentage;
    this.installedAt = installedAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() { return id; }
  public MachineEntity getMachine() { return machine; }
  public SparepartEntity getSparepart() { return sparepart; }
  public long getExpectedProductionCount() { return expectedProductionCount; }
  public long getBaselineCounter() { return baselineCounter; }
  public int getThresholdPercentage() { return thresholdPercentage; }
  public Instant getInstalledAt() { return installedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }

  public void update(long expectedProductionCount, long baselineCounter, int thresholdPercentage, Instant updatedAt) {
    this.expectedProductionCount = expectedProductionCount;
    this.baselineCounter = baselineCounter;
    this.thresholdPercentage = thresholdPercentage;
    this.updatedAt = updatedAt;
  }
}
