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
 * Persisted {@code rating_dimensions} row (story 10-8, AD-14). Dimensions are shared
 * configuration data (FR-124 "shared dimension config with FR-121") — both TECHNICIAN
 * and WORKORDER ratings use the same dimension set. Managed by SUPER_ADMIN; seeded
 * with a default set (Speed / Work Quality / Tidiness).
 */
@Entity
@Table(name = "rating_dimensions")
public class RatingDimensionEntity {

  @Id
  private UUID id;

  @Column(nullable = false, length = 40)
  private String code;

  @Column(nullable = false, length = 100)
  private String label;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected RatingDimensionEntity() {
  }

  public RatingDimensionEntity(UUID id, String code, String label, int sortOrder, UUID createdBy,
      Instant createdAt) {
    this.id = id;
    this.code = code;
    this.label = label;
    this.sortOrder = sortOrder;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
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

  /** Updates the mutable label/sort_order; the code is the identity and never changes. */
  public void update(String label, int sortOrder, Instant updatedAt) {
    this.label = label;
    this.sortOrder = sortOrder;
    this.updatedAt = updatedAt;
  }
}