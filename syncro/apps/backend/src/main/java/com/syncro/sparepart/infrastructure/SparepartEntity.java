package com.syncro.sparepart.infrastructure;

import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.sparepart.domain.BomReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

  @Column(name = "hierarchy_identity_key", length = 255)
  private String hierarchyIdentityKey;

  @Column(name = "bom_serial", length = 100)
  private String bomSerial;

  @Column(name = "bom_code", length = 100)
  private String bomCode;

  @Column(name = "bom_code_version")
  private Integer bomCodeVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "review_status", nullable = false, length = 20)
  private BomReviewStatus reviewStatus;

  @Column(name = "rejection_reason")
  private String rejectionReason;

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
    this.reviewStatus = BomReviewStatus.PENDING_REVIEW;
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

  public String getHierarchyIdentityKey() {
    return hierarchyIdentityKey;
  }

  public String getBomSerial() {
    return bomSerial;
  }

  public String getBomCode() {
    return bomCode;
  }

  public Integer getBomCodeVersion() {
    return bomCodeVersion;
  }

  public BomReviewStatus getReviewStatus() {
    return reviewStatus;
  }

  public String getRejectionReason() {
    return rejectionReason;
  }

  /**
   * Replaces the BOM master identity (Story 18-1). {@code bomCodeVersion} is owned by the
   * caller: the service bumps it only when the derived code changes (prefix re-allocation).
   */
  public void updateBomIdentity(String hierarchyIdentityKey, String bomSerial, String bomCode,
      Integer bomCodeVersion, Instant updatedAt) {
    this.hierarchyIdentityKey = hierarchyIdentityKey;
    this.bomSerial = bomSerial;
    this.bomCode = bomCode;
    this.bomCodeVersion = bomCodeVersion;
    this.updatedAt = updatedAt;
  }

  /** PENDING_REVIEW → ACTIVE (terminal). The caller enforces the transition precondition. */
  public void approve(Instant updatedAt) {
    this.reviewStatus = BomReviewStatus.ACTIVE;
    this.rejectionReason = null;
    this.updatedAt = updatedAt;
  }

  /** PENDING_REVIEW → REJECTED with a required reason (terminal). */
  public void reject(String rejectionReason, Instant updatedAt) {
    this.reviewStatus = BomReviewStatus.REJECTED;
    this.rejectionReason = rejectionReason;
    this.updatedAt = updatedAt;
  }

  /**
   * Re-queues for review (Story 18-1): a BOM identity change (prefix re-allocation) sends an
   * already-reviewed sparepart back to PENDING_REVIEW and clears any prior rejection reason, so
   * the new code is re-approved. Stable-prefix edits never call this.
   */
  public void reopenForReview(Instant updatedAt) {
    this.reviewStatus = BomReviewStatus.PENDING_REVIEW;
    this.rejectionReason = null;
    this.updatedAt = updatedAt;
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
