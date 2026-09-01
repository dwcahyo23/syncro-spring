package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code work_log_ratings} row (blueprint C2, story 15-2). One score per
 * {@code (work_log_id, criterion_id)} (V1 unique); ratings of a submitted work log
 * are immutable after submission per FR-113 — no mutation methods on purpose.
 */
@Entity
@Table(name = "work_log_ratings")
public class WorkLogRatingEntity {

  @Id
  private UUID id;

  @Column(name = "work_log_id", nullable = false)
  private UUID workLogId;

  @Column(name = "criterion_id", nullable = false)
  private UUID criterionId;

  @Column(nullable = false)
  private int score;

  @Column(name = "rated_by")
  private UUID ratedBy;

  @Column(name = "rated_at", nullable = false)
  private Instant ratedAt;

  @Column(columnDefinition = "text")
  private String remarks;

  protected WorkLogRatingEntity() {
  }

  public WorkLogRatingEntity(UUID id, UUID workLogId, UUID criterionId, int score, UUID ratedBy,
      Instant ratedAt, String remarks) {
    this.id = id;
    this.workLogId = workLogId;
    this.criterionId = criterionId;
    this.score = score;
    this.ratedBy = ratedBy;
    this.ratedAt = ratedAt;
    this.remarks = remarks;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkLogId() {
    return workLogId;
  }

  public UUID getCriterionId() {
    return criterionId;
  }

  public int getScore() {
    return score;
  }

  public UUID getRatedBy() {
    return ratedBy;
  }

  public Instant getRatedAt() {
    return ratedAt;
  }

  public String getRemarks() {
    return remarks;
  }
}
