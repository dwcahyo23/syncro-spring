package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Persisted {@code work_order_rating_criterion_categories} row (blueprint C3, story
 * 15-2). Pivot limiting a workorder rating criterion to specific work-order
 * categories; unique {@code (criterion_id, category_id)} via V1 constraint.
 */
@Entity
@Table(name = "work_order_rating_criterion_categories")
public class WorkOrderRatingCriterionCategoryEntity {

  @Id
  private UUID id;

  @Column(name = "criterion_id", nullable = false)
  private UUID criterionId;

  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  protected WorkOrderRatingCriterionCategoryEntity() {
  }

  public WorkOrderRatingCriterionCategoryEntity(UUID id, UUID criterionId, UUID categoryId) {
    this.id = id;
    this.criterionId = criterionId;
    this.categoryId = categoryId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCriterionId() {
    return criterionId;
  }

  public UUID getCategoryId() {
    return categoryId;
  }
}
