package com.syncro.kpi.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted {@code kpi_monthly_breakdowns} row (blueprint G2, story 15-2). Materialized
 * breakdown count per (plant, month); unique via V1 constraint. Rows are recomputed
 * by the KPI refresh job — no in-entity mutation methods.
 */
@Entity
@Table(name = "kpi_monthly_breakdowns")
public class KpiMonthlyBreakdownEntity {

  @Id
  private UUID id;

  @Column(name = "plant_id", nullable = false)
  private UUID plantId;

  @Column(nullable = false)
  private LocalDate month;

  @Column(nullable = false)
  private int count;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected KpiMonthlyBreakdownEntity() {
  }

  public KpiMonthlyBreakdownEntity(UUID id, UUID plantId, LocalDate month, int count,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.plantId = plantId;
    this.month = month;
    this.count = count;
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

  public int getCount() {
    return count;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
