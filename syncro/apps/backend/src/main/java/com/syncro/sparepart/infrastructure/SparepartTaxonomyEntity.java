package com.syncro.sparepart.infrastructure;

import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sparepart_taxonomy")
public class SparepartTaxonomyEntity {
  @Id
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private SparepartTaxonomyDimension dimension;

  @Column(nullable = false, length = 64)
  private String code;

  @Column(nullable = false, length = 255)
  private String name;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "category_id")
  private SparepartTaxonomyEntity category;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SparepartTaxonomyEntity() {
  }

  public SparepartTaxonomyEntity(
      UUID id,
      SparepartTaxonomyDimension dimension,
      String code,
      String name,
      Instant createdAt,
      Instant updatedAt) {
    this(id, dimension, code, name, null, createdAt, updatedAt);
  }

  public SparepartTaxonomyEntity(
      UUID id,
      SparepartTaxonomyDimension dimension,
      String code,
      String name,
      SparepartTaxonomyEntity category,
      Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.dimension = dimension;
    this.code = code;
    this.name = name;
    this.category = category;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public SparepartTaxonomyDimension getDimension() {
    return dimension;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public SparepartTaxonomyEntity getCategory() {
    return category;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void update(String code, String name, SparepartTaxonomyEntity category, Instant updatedAt) {
    this.code = code;
    this.name = name;
    this.category = category;
    this.updatedAt = updatedAt;
  }
}
