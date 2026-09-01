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
 * Persisted {@code kpi_mar_monthlies} row (blueprint G5, story 15-2). Materialized
 * machine availability ratio per (plant, month) with its input minutes and the
 * source-status provenance (from telemetry ingest state); unique via V1 constraint.
 */
@Entity
@Table(name = "kpi_mar_monthlies")
public class KpiMarMonthlyEntity {

  @Id
  private UUID id;

  @Column(name = "plant_id", nullable = false)
  private UUID plantId;

  @Column(nullable = false)
  private LocalDate month;

  @Column(name = "planned_available_minutes", nullable = false)
  private int plannedAvailableMinutes;

  @Column(name = "downtime_minutes", nullable = false)
  private int downtimeMinutes;

  @Column(name = "mar_percent", precision = 5, scale = 2)
  private BigDecimal marPercent;

  @Column(name = "source_status", length = 20)
  private String sourceStatus;

  @Column(name = "source_message", columnDefinition = "text")
  private String sourceMessage;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected KpiMarMonthlyEntity() {
  }

  public KpiMarMonthlyEntity(UUID id, UUID plantId, LocalDate month, int plannedAvailableMinutes,
      int downtimeMinutes, BigDecimal marPercent, String sourceStatus, String sourceMessage,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.plantId = plantId;
    this.month = month;
    this.plannedAvailableMinutes = plannedAvailableMinutes;
    this.downtimeMinutes = downtimeMinutes;
    this.marPercent = marPercent;
    this.sourceStatus = sourceStatus;
    this.sourceMessage = sourceMessage;
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

  public int getPlannedAvailableMinutes() {
    return plannedAvailableMinutes;
  }

  public int getDowntimeMinutes() {
    return downtimeMinutes;
  }

  public BigDecimal getMarPercent() {
    return marPercent;
  }

  public String getSourceStatus() {
    return sourceStatus;
  }

  public String getSourceMessage() {
    return sourceMessage;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
