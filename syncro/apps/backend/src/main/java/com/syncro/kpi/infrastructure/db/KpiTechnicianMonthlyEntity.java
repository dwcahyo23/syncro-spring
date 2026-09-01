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
 * Persisted {@code kpi_technician_monthlies} row (blueprint G6, story 15-2).
 * Materialized technician performance per (plant, technician, month); unique via V1
 * constraint.
 */
@Entity
@Table(name = "kpi_technician_monthlies")
public class KpiTechnicianMonthlyEntity {

  @Id
  private UUID id;

  @Column(name = "plant_id", nullable = false)
  private UUID plantId;

  @Column(name = "technician_id", nullable = false)
  private UUID technicianId;

  @Column(nullable = false)
  private LocalDate month;

  @Column(name = "average_rating", precision = 4, scale = 2)
  private BigDecimal averageRating;

  @Column(name = "total_wo", nullable = false)
  private int totalWo;

  @Column(name = "first_time_fix_rate", precision = 5, scale = 2)
  private BigDecimal firstTimeFixRate;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected KpiTechnicianMonthlyEntity() {
  }

  public KpiTechnicianMonthlyEntity(UUID id, UUID plantId, UUID technicianId, LocalDate month,
      BigDecimal averageRating, int totalWo, BigDecimal firstTimeFixRate, Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.plantId = plantId;
    this.technicianId = technicianId;
    this.month = month;
    this.averageRating = averageRating;
    this.totalWo = totalWo;
    this.firstTimeFixRate = firstTimeFixRate;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPlantId() {
    return plantId;
  }

  public UUID getTechnicianId() {
    return technicianId;
  }

  public LocalDate getMonth() {
    return month;
  }

  public BigDecimal getAverageRating() {
    return averageRating;
  }

  public int getTotalWo() {
    return totalWo;
  }

  public BigDecimal getFirstTimeFixRate() {
    return firstTimeFixRate;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
