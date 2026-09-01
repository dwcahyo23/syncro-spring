package com.syncro.inventory.infrastructure.db;

import com.syncro.inventory.domain.InventoryTransferStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code inventory_transfers} row (blueprint E3, story 15-2). Stock moved
 * between two locations of the same sparepart; source != destination enforced by
 * the V1 CHECK. Cross-aggregate references (sparepart, locations, actors) are plain
 * UUID columns (AD-3/AD-4).
 */
@Entity
@Table(name = "inventory_transfers")
public class InventoryTransferEntity {

  @Id
  private UUID id;

  @Column(name = "sparepart_id", nullable = false)
  private UUID sparepartId;

  @Column(name = "source_location_id", nullable = false)
  private UUID sourceLocationId;

  @Column(name = "destination_location_id", nullable = false)
  private UUID destinationLocationId;

  @Column(nullable = false, precision = 18, scale = 2)
  private BigDecimal quantity;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private InventoryTransferStatus status;

  @Column(name = "requested_by")
  private UUID requestedBy;

  @Column(name = "reviewed_by")
  private UUID reviewedBy;

  @Column(name = "rejection_reason", columnDefinition = "text")
  private String rejectionReason;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected InventoryTransferEntity() {
  }

  public InventoryTransferEntity(UUID id, UUID sparepartId, UUID sourceLocationId,
      UUID destinationLocationId, BigDecimal quantity, InventoryTransferStatus status,
      UUID requestedBy, UUID reviewedBy, String rejectionReason, Instant reviewedAt,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.sparepartId = sparepartId;
    this.sourceLocationId = sourceLocationId;
    this.destinationLocationId = destinationLocationId;
    this.quantity = quantity;
    this.status = status;
    this.requestedBy = requestedBy;
    this.reviewedBy = reviewedBy;
    this.rejectionReason = rejectionReason;
    this.reviewedAt = reviewedAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSparepartId() {
    return sparepartId;
  }

  public UUID getSourceLocationId() {
    return sourceLocationId;
  }

  public UUID getDestinationLocationId() {
    return destinationLocationId;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public InventoryTransferStatus getStatus() {
    return status;
  }

  public UUID getRequestedBy() {
    return requestedBy;
  }

  public UUID getReviewedBy() {
    return reviewedBy;
  }

  public String getRejectionReason() {
    return rejectionReason;
  }

  public Instant getReviewedAt() {
    return reviewedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Approval decision (E3): stamps reviewer, verdict time, and optional reason. */
  public void review(InventoryTransferStatus status, UUID reviewedBy, String rejectionReason,
      Instant reviewedAt, Instant updatedAt) {
    this.status = status;
    this.reviewedBy = reviewedBy;
    this.rejectionReason = rejectionReason;
    this.reviewedAt = reviewedAt;
    this.updatedAt = updatedAt;
  }
}
