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
 * Persisted {@code kpi_targets} row (blueprint G1, story 15-2). Per-plant monthly
 * target set ({@code month} is a DATE normalized to the first of the month);
 * unique {@code (plant_id, month)} via V1 constraint.
 */
@Entity
@Table(name = "kpi_targets")
public class KpiTargetEntity {

  @Id
  private UUID id;

  @Column(name = "plant_id", nullable = false)
  private UUID plantId;

  @Column(nullable = false)
  private LocalDate month;

  @Column(name = "monthly_breakdown_target")
  private Integer monthlyBreakdownTarget;

  @Column(name = "mtbf_target_days", precision = 12, scale = 2)
  private BigDecimal mtbfTargetDays;

  @Column(name = "mttr_target_minutes", precision = 12, scale = 2)
  private BigDecimal mttrTargetMinutes;

  @Column(name = "oee_quality_percent", precision = 5, scale = 2)
  private BigDecimal oeeQualityPercent;

  @Column(name = "oee_performance_percent", precision = 5, scale = 2)
  private BigDecimal oeePerformancePercent;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected KpiTargetEntity() {
  }

  public KpiTargetEntity(UUID id, UUID plantId, LocalDate month, Integer monthlyBreakdownTarget,
      BigDecimal mtbfTargetDays, BigDecimal mttrTargetMinutes, BigDecimal oeeQualityPercent,
      BigDecimal oeePerformancePercent, UUID createdBy, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.plantId = plantId;
    this.month = month;
    this.monthlyBreakdownTarget = monthlyBreakdownTarget;
    this.mtbfTargetDays = mtbfTargetDays;
    this.mttrTargetMinutes = mttrTargetMinutes;
    this.oeeQualityPercent = oeeQualityPercent;
    this.oeePerformancePercent = oeePerformancePercent;
    this.createdBy = createdBy;
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

  public Integer getMonthlyBreakdownTarget() {
    return monthlyBreakdownTarget;
  }

  public BigDecimal getMtbfTargetDays() {
    return mtbfTargetDays;
  }

  public BigDecimal getMttrTargetMinutes() {
    return mttrTargetMinutes;
  }

  public BigDecimal getOeeQualityPercent() {
    return oeeQualityPercent;
  }

  public BigDecimal getOeePerformancePercent() {
    return oeePerformancePercent;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
