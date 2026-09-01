package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code work_order_rating_criteria} row (blueprint C3, story 15-2).
 * Workorder-level quality rating dimension — the analog of
 * {@link WorkLogRatingCriterionEntity} for the {@code work_order_quality_ratings}
 * flow.
 */
@Entity
@Table(name = "work_order_rating_criteria")
public class WorkOrderRatingCriterionEntity {

  @Id
  private UUID id;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "min_score", nullable = false)
  private int minScore;

  @Column(name = "max_score", nullable = false)
  private int maxScore;

  @Column(name = "plant_id")
  private UUID plantId;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WorkOrderRatingCriterionEntity() {
  }

  public WorkOrderRatingCriterionEntity(UUID id, String name, String description, int minScore,
      int maxScore, UUID plantId, boolean active, int sortOrder, UUID createdBy,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.name = name;
    this.description = description;
    this.minScore = minScore;
    this.maxScore = maxScore;
    this.plantId = plantId;
    this.active = active;
    this.sortOrder = sortOrder;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public int getMinScore() {
    return minScore;
  }

  public int getMaxScore() {
    return maxScore;
  }

  public UUID getPlantId() {
    return plantId;
  }

  public boolean isActive() {
    return active;
  }

  public int getSortOrder() {
    return sortOrder;
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
