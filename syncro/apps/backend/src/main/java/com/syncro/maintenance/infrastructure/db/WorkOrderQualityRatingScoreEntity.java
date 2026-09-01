package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Persisted {@code work_order_quality_rating_scores} row (blueprint C6, story 15-2).
 * Per-criterion score of a quality rating; unique
 * {@code (quality_rating_id, criterion_id)} via V1 constraint.
 */
@Entity
@Table(name = "work_order_quality_rating_scores")
public class WorkOrderQualityRatingScoreEntity {

  @Id
  private UUID id;

  @Column(name = "quality_rating_id", nullable = false)
  private UUID qualityRatingId;

  @Column(name = "criterion_id", nullable = false)
  private UUID criterionId;

  @Column(nullable = false)
  private int score;

  protected WorkOrderQualityRatingScoreEntity() {
  }

  public WorkOrderQualityRatingScoreEntity(UUID id, UUID qualityRatingId, UUID criterionId,
      int score) {
    this.id = id;
    this.qualityRatingId = qualityRatingId;
    this.criterionId = criterionId;
    this.score = score;
  }

  public UUID getId() {
    return id;
  }

  public UUID getQualityRatingId() {
    return qualityRatingId;
  }

  public UUID getCriterionId() {
    return criterionId;
  }

  public int getScore() {
    return score;
  }
}
