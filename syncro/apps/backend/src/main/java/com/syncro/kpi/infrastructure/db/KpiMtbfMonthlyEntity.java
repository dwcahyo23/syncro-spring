package com.syncro.kpi.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted {@code kpi_mtbf_monthlies} row (blueprint G3, story 15-2). Materialized
 * MTBF (days) per (machine, month); unique via V1 constraint.
 */
@Entity
@Table(name = "kpi_mtbf_monthlies")
public class KpiMtbfMonthlyEntity {

  @Id
  private UUID id;

  @Column(name = "plant_id", nullable = false)
  private UUID plantId;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Column(nullable = false)
  private LocalDate month;

  @Column(name = "mtbf_days", precision = 12, scale = 2)
  private BigDecimal mtbfDays;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected KpiMtbfMonthlyEntity() {
  }

  public KpiMtbfMonthlyEntity(UUID id, UUID plantId, UUID machineId, LocalDate month,
      BigDecimal mtbfDays, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.plantId = plantId;
    this.machineId = machineId;
    this.month = month;
    this.mtbfDays = mtbfDays;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPlantId() {
    return plantId;
  }

  public UUID getMachineId() {
    return machineId;
  }

  public LocalDate getMonth() {
    return month;
  }

  public BigDecimal getMtbfDays() {
    return mtbfDays;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
