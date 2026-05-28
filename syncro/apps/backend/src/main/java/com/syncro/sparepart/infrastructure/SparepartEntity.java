package com.syncro.sparepart.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "spareparts")
public class SparepartEntity {
  @Id
  private UUID id;

  @Column(nullable = false, length = 64)
  private String code;

  @Column(nullable = false, length = 255)
  private String name;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "category_id", nullable = false)
  private SparepartTaxonomyEntity category;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "brand_id", nullable = false)
  private SparepartTaxonomyEntity brand;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "kind_id", nullable = false)
  private SparepartTaxonomyEntity kind;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "type_id", nullable = false)
  private SparepartTaxonomyEntity type;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SparepartEntity() {
  }

  public SparepartEntity(
      UUID id,
      String code,
      String name,
      SparepartTaxonomyEntity category,
      SparepartTaxonomyEntity brand,
      SparepartTaxonomyEntity kind,
      SparepartTaxonomyEntity type,
      Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.code = code;
    this.name = name;
    this.category = category;
    this.brand = brand;
    this.kind = kind;
    this.type = type;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
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

  public SparepartTaxonomyEntity getBrand() {
    return brand;
  }

  public SparepartTaxonomyEntity getKind() {
    return kind;
  }

  public SparepartTaxonomyEntity getType() {
    return type;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void update(
      String code,
      String name,
      SparepartTaxonomyEntity category,
      SparepartTaxonomyEntity brand,
      SparepartTaxonomyEntity kind,
      SparepartTaxonomyEntity type,
      Instant updatedAt) {
    this.code = code;
    this.name = name;
    this.category = category;
    this.brand = brand;
    this.kind = kind;
    this.type = type;
    this.updatedAt = updatedAt;
  }
}
