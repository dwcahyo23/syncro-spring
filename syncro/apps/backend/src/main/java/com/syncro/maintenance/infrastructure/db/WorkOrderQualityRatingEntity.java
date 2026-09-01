package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.WorkRatingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code work_order_quality_ratings} row (blueprint C4, story 15-2). One
 * per workorder ({@code uq_work_order_quality_ratings_work_order}); the three
 * headline scores are the legacy columns, the per-criterion detail lives in
 * {@code work_order_quality_rating_scores}. Technicians rated alongside the WO are
 * in {@code work_order_quality_rating_technicians}.
 */
@Entity
@Table(name = "work_order_quality_ratings")
public class WorkOrderQualityRatingEntity {

  @Id
  private UUID id;

  @Column(name = "work_order_id", nullable = false, length = 50)
  private String workOrderId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private WorkRatingStatus status;

  @Column(name = "due_at")
  private Instant dueAt;

  @Column(name = "submitted_at")
  private Instant submittedAt;

  @Column(name = "submitted_by")
  private UUID submittedBy;

  @Column(name = "cleanliness_score")
  private Integer cleanlinessScore;

  @Column(name = "tidiness_score")
  private Integer tidinessScore;

  @Column(name = "speed_score")
  private Integer speedScore;

  @Column(columnDefinition = "text")
  private String remarks;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WorkOrderQualityRatingEntity() {
  }

  public WorkOrderQualityRatingEntity(UUID id, String workOrderId, WorkRatingStatus status,
      Instant dueAt, Instant submittedAt, UUID submittedBy, Integer cleanlinessScore,
      Integer tidinessScore, Integer speedScore, String remarks, Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.workOrderId = workOrderId;
    this.status = status;
    this.dueAt = dueAt;
    this.submittedAt = submittedAt;
    this.submittedBy = submittedBy;
    this.cleanlinessScore = cleanlinessScore;
    this.tidinessScore = tidinessScore;
    this.speedScore = speedScore;
    this.remarks = remarks;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getWorkOrderId() {
    return workOrderId;
  }

  public WorkRatingStatus getStatus() {
    return status;
  }

  public Instant getDueAt() {
    return dueAt;
  }

  public Instant getSubmittedAt() {
    return submittedAt;
  }

  public UUID getSubmittedBy() {
    return submittedBy;
  }

  public Integer getCleanlinessScore() {
    return cleanlinessScore;
  }

  public Integer getTidinessScore() {
    return tidinessScore;
  }

  public Integer getSpeedScore() {
    return speedScore;
  }

  public String getRemarks() {
    return remarks;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Submission transition (C4): stamps who submitted, when, with the scores. */
  public void submit(Integer cleanlinessScore, Integer tidinessScore, Integer speedScore,
      UUID submittedBy, Instant submittedAt, Instant updatedAt) {
    this.cleanlinessScore = cleanlinessScore;
    this.tidinessScore = tidinessScore;
    this.speedScore = speedScore;
    this.submittedBy = submittedBy;
    this.submittedAt = submittedAt;
    this.status = WorkRatingStatus.SUBMITTED;
    this.updatedAt = updatedAt;
  }

  /** Expiry transition (C4): marks the rating as EXPIRED when due_at passes unsubmitted. */
  public void setStatus(WorkRatingStatus status) {
    this.status = status;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
