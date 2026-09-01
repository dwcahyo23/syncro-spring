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
 * Persisted {@code kpi_mttr_monthlies} row (blueprint G4, story 15-2). Materialized
 * MTTR (minutes) per (plant, month) in both wall-clock and actual-working variants;
 * unique via V1 constraint.
 */
@Entity
@Table(name = "kpi_mttr_monthlies")
public class KpiMttrMonthlyEntity {

  @Id
  private UUID id;

  @Column(name = "plant_id", nullable = false)
  private UUID plantId;

  @Column(nullable = false)
  private LocalDate month;

  @Column(name = "wall_clock_mttr_minutes", precision = 12, scale = 2)
  private BigDecimal wallClockMttrMinutes;

  @Column(name = "actual_working_mttr_minutes", precision = 12, scale = 2)
  private BigDecimal actualWorkingMttrMinutes;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected KpiMttrMonthlyEntity() {
  }

  public KpiMttrMonthlyEntity(UUID id, UUID plantId, LocalDate month,
      BigDecimal wallClockMttrMinutes, BigDecimal actualWorkingMttrMinutes, Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.plantId = plantId;
    this.month = month;
    this.wallClockMttrMinutes = wallClockMttrMinutes;
    this.actualWorkingMttrMinutes = actualWorkingMttrMinutes;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPlantId() {
    return plantId;
  }

  public LocalDate getMonth() {
    return month;
  }

  public BigDecimal getWallClockMttrMinutes() {
    return wallClockMttrMinutes;
  }

  public BigDecimal getActualWorkingMttrMinutes() {
    return actualWorkingMttrMinutes;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
