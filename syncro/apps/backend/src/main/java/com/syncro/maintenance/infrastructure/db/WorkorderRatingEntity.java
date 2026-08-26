package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.RatingType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code workorder_ratings} row (story 10-8, FR-121/FR-124). Each rating is
 * immutable after submission — the unique constraints enforce exactly one TECHNICIAN
 * rating per (workorder, rated_user) and exactly one WORKORDER rating per workorder
 * (partial unique index WHERE rating_type='WORKORDER'). {@code ratedUserId} is null
 * for a WORKORDER-type rating.
 */
@Entity
@Table(name = "workorder_ratings")
public class WorkorderRatingEntity {

  @Id
  private UUID id;

  @Column(name = "workorder_id", nullable = false, length = 50)
  private String workorderId;

  @Enumerated(EnumType.STRING)
  @Column(name = "rating_type", nullable = false, length = 12)
  private RatingType ratingType;

  @Column(name = "rated_user_id")
  private UUID ratedUserId;

  @Column(name = "rater_user_id", nullable = false)
  private UUID raterUserId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected WorkorderRatingEntity() {
  }

  public WorkorderRatingEntity(UUID id, String workorderId, RatingType ratingType, UUID ratedUserId,
      UUID raterUserId, Instant createdAt) {
    this.id = id;
    this.workorderId = workorderId;
    this.ratingType = ratingType;
    this.ratedUserId = ratedUserId;
    this.raterUserId = raterUserId;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getWorkorderId() {
    return workorderId;
  }

  public RatingType getRatingType() {
    return ratingType;
  }

  public UUID getRatedUserId() {
    return ratedUserId;
  }

  public UUID getRaterUserId() {
    return raterUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}