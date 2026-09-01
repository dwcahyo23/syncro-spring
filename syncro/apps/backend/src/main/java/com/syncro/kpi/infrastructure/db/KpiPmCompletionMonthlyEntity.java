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
 * Persisted {@code kpi_pm_completion_monthlies} row (blueprint G7, story 15-2).
 * Materialized PM completion rate per (plant, month) with planned/completed counts
 * and source-status provenance; unique via V1 constraint.
 */
@Entity
@Table(name = "kpi_pm_completion_monthlies")
public class KpiPmCompletionMonthlyEntity {

  @Id
  private UUID id;

  @Column(name = "plant_id", nullable = false)
  private UUID plantId;

  @Column(nullable = false)
  private LocalDate month;

  @Column(name = "completion_rate", precision = 5, scale = 2)
  private BigDecimal completionRate;

  @Column(name = "completed_count", nullable = false)
  private int completedCount;

  @Column(name = "planned_count", nullable = false)
  private int plannedCount;

  @Column(name = "source_status", length = 20)
  private String sourceStatus;

  @Column(name = "source_message", columnDefinition = "text")
  private String sourceMessage;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected KpiPmCompletionMonthlyEntity() {
  }

  public KpiPmCompletionMonthlyEntity(UUID id, UUID plantId, LocalDate month,
      BigDecimal completionRate, int completedCount, int plannedCount, String sourceStatus,
      String sourceMessage, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.plantId = plantId;
    this.month = month;
    this.completionRate = completionRate;
    this.completedCount = completedCount;
    this.plannedCount = plannedCount;
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

  public BigDecimal getCompletionRate() {
    return completionRate;
  }

  public int getCompletedCount() {
    return completedCount;
  }

  public int getPlannedCount() {
    return plannedCount;
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
