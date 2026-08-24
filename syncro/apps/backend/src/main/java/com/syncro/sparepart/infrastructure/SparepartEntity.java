package com.syncro.sparepart.infrastructure;

import com.syncro.machine.infrastructure.MachineEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
  @JoinColumn(name = "machine_id", nullable = false)
  private MachineEntity machine;

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

  @Column(name = "material_code", length = 64)
  private String materialCode;

  @Column(name = "lead_time_hours", precision = 12, scale = 2)
  private BigDecimal leadTimeHours;

  @Column(name = "image_object_key", length = 255)
  private String imageObjectKey;

  protected SparepartEntity() {
  }

  public SparepartEntity(
      UUID id,
      String code,
      String name,
      MachineEntity machine,
      SparepartTaxonomyEntity category,
      SparepartTaxonomyEntity brand,
      SparepartTaxonomyEntity kind,
      SparepartTaxonomyEntity type,
      Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.code = code;
    this.name = name;
    this.machine = machine;
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

  public MachineEntity getMachine() {
    return machine;
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

  public String getMaterialCode() {
    return materialCode;
  }

  public BigDecimal getLeadTimeHours() {
    return leadTimeHours;
  }

  public String getImageObjectKey() {
    return imageObjectKey;
  }

  public void update(
      String code,
      String name,
      MachineEntity machine,
      SparepartTaxonomyEntity category,
      SparepartTaxonomyEntity brand,
      SparepartTaxonomyEntity kind,
      SparepartTaxonomyEntity type,
      Instant updatedAt) {
    this.code = code;
    this.name = name;
    this.machine = machine;
    this.category = category;
    this.brand = brand;
    this.kind = kind;
    this.type = type;
    this.updatedAt = updatedAt;
  }

  /**
   * Replaces only the procurement-readiness subset (Story 8-2). {@code null} clears a value.
   * Identity, taxonomy, and lifecycle columns are untouched.
   */
  public void updateProcurement(String materialCode, BigDecimal leadTimeHours, Instant updatedAt) {
    this.materialCode = materialCode;
    this.leadTimeHours = leadTimeHours;
    this.updatedAt = updatedAt;
  }

  /**
   * Replaces the image object key (Story 8-4). {@code null} clears the reference.
   * The old Garage object must be deleted by the caller before calling this.
   */
  public void updateImageObjectKey(String imageObjectKey, Instant updatedAt) {
    this.imageObjectKey = imageObjectKey;
    this.updatedAt = updatedAt;
  }
}
