package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted {@code workorder_rating_scores} row (story 10-8). Normalized child table
 * because dimensions are dynamic — a column-per-dimension schema would need a migration
 * whenever SUPER_ADMIN adds a dimension. PK is (rating_id, dimension_id); score is
 * SMALLINT 1-5. FK ON DELETE CASCADE removes scores when the parent rating is removed.
 */
@Entity
@Table(name = "workorder_rating_scores")
@IdClass(WorkorderRatingScoreEntity.WorkorderRatingScoreId.class)
public class WorkorderRatingScoreEntity {

  @Id
  @Column(name = "rating_id", nullable = false)
  private UUID ratingId;

  @Id
  @Column(name = "dimension_id", nullable = false)
  private UUID dimensionId;

  @Column(nullable = false)
  private short score;

  protected WorkorderRatingScoreEntity() {
  }

  public WorkorderRatingScoreEntity(UUID ratingId, UUID dimensionId, short score) {
    this.ratingId = ratingId;
    this.dimensionId = dimensionId;
    this.score = score;
  }

  public UUID getRatingId() {
    return ratingId;
  }

  public UUID getDimensionId() {
    return dimensionId;
  }

  public short getScore() {
    return score;
  }

  public static class WorkorderRatingScoreId implements Serializable {
    private UUID ratingId;
    private UUID dimensionId;

    public WorkorderRatingScoreId() {
    }

    public WorkorderRatingScoreId(UUID ratingId, UUID dimensionId) {
      this.ratingId = ratingId;
      this.dimensionId = dimensionId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof WorkorderRatingScoreId that)) return false;
      return Objects.equals(ratingId, that.ratingId) && Objects.equals(dimensionId, that.dimensionId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ratingId, dimensionId);
    }
  }
}