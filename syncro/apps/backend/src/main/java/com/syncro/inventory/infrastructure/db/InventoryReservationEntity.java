package com.syncro.inventory.infrastructure.db;

import com.syncro.inventory.domain.InventoryReservationStatus;
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
 * Persisted {@code inventory_reservations} row (blueprint E4, story 15-2). Holds
 * stock at one location for a downstream reference (workorder, request);
 * {@code remaining_quantity} tracks the un-consumed part as reservations are drawn
 * down. Cross-aggregate references are plain UUID columns (AD-3/AD-4).
 */
@Entity
@Table(name = "inventory_reservations")
public class InventoryReservationEntity {

  @Id
  private UUID id;

  @Column(name = "sparepart_id", nullable = false)
  private UUID sparepartId;

  @Column(name = "location_id", nullable = false)
  private UUID locationId;

  @Column(nullable = false, precision = 18, scale = 2)
  private BigDecimal quantity;

  @Column(name = "remaining_quantity", nullable = false, precision = 18, scale = 2)
  private BigDecimal remainingQuantity;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private InventoryReservationStatus status;

  @Column(name = "reference_type", length = 50)
  private String referenceType;

  @Column(name = "reference_id", length = 64)
  private String referenceId;

  @Column(name = "requested_by")
  private UUID requestedBy;

  @Column(name = "consumed_by")
  private UUID consumedBy;

  @Column(name = "cancelled_by")
  private UUID cancelledBy;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected InventoryReservationEntity() {
  }

  public InventoryReservationEntity(UUID id, UUID sparepartId, UUID locationId,
      BigDecimal quantity, BigDecimal remainingQuantity, InventoryReservationStatus status,
      String referenceType, String referenceId, UUID requestedBy, UUID consumedBy,
      UUID cancelledBy, Instant expiresAt, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.sparepartId = sparepartId;
    this.locationId = locationId;
    this.quantity = quantity;
    this.remainingQuantity = remainingQuantity;
    this.status = status;
    this.referenceType = referenceType;
    this.referenceId = referenceId;
    this.requestedBy = requestedBy;
    this.consumedBy = consumedBy;
    this.cancelledBy = cancelledBy;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSparepartId() {
    return sparepartId;
  }

  public UUID getLocationId() {
    return locationId;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public BigDecimal getRemainingQuantity() {
    return remainingQuantity;
  }

  public InventoryReservationStatus getStatus() {
    return status;
  }

  public String getReferenceType() {
    return referenceType;
  }

  public String getReferenceId() {
    return referenceId;
  }

  public UUID getRequestedBy() {
    return requestedBy;
  }

  public UUID getConsumedBy() {
    return consumedBy;
  }

  public UUID getCancelledBy() {
    return cancelledBy;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Draws down the remaining quantity (E4 consume path); caller owns the status. */
  public void consume(BigDecimal delta, UUID consumedBy, Instant updatedAt) {
    this.remainingQuantity = this.remainingQuantity.subtract(delta);
    this.consumedBy = consumedBy;
    this.updatedAt = updatedAt;
  }

  /** Cancels the reservation (E4 cancel path): stamps who and keeps remaining as-is. */
  public void cancel(UUID cancelledBy, Instant updatedAt) {
    this.status = InventoryReservationStatus.CANCELLED;
    this.cancelledBy = cancelledBy;
    this.updatedAt = updatedAt;
  }
}
