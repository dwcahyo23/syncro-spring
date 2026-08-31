package com.syncro.inventory.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code inventory_stock_balances} row (blueprint E2, story 15-1). UUID
 * primary key over the unique {@code (sparepart_id, location_id)} pair; stock
 * semantics are available/reserved/consumed/minimum_stock, replacing the legacy
 * stock_on_hand/order_point/order_qty model. {@code consumed} is the lifetime
 * running total; the reorder signal is the derived rule
 * {@code available <= minimum_stock} (POC proposal §4). Negative stock is guarded
 * at the repository layer by the atomic conditional update, never by a DB CHECK.
 */
@Entity
@Table(name = "inventory_stock_balances")
public class InventoryStockBalanceEntity {

  @Id
  private UUID id;

  @Column(name = "sparepart_id", nullable = false)
  private UUID sparepartId;

  @Column(name = "location_id", nullable = false)
  private UUID locationId;

  @Column(nullable = false, precision = 18, scale = 2)
  private BigDecimal available;

  @Column(nullable = false, precision = 18, scale = 2)
  private BigDecimal reserved;

  @Column(nullable = false, precision = 18, scale = 2)
  private BigDecimal consumed;

  @Column(name = "minimum_stock", nullable = false, precision = 18, scale = 2)
  private BigDecimal minimumStock;

  @Version
  @Column(nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected InventoryStockBalanceEntity() {
  }

  public InventoryStockBalanceEntity(UUID id, UUID sparepartId, UUID locationId,
      BigDecimal available, BigDecimal reserved, BigDecimal consumed, BigDecimal minimumStock,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.sparepartId = sparepartId;
    this.locationId = locationId;
    this.available = available;
    this.reserved = reserved;
    this.consumed = consumed;
    this.minimumStock = minimumStock;
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

  public BigDecimal getAvailable() {
    return available;
  }

  public BigDecimal getReserved() {
    return reserved;
  }

  public BigDecimal getConsumed() {
    return consumed;
  }

  public BigDecimal getMinimumStock() {
    return minimumStock;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Full overwrite of the provided fields (PUT semantics). */
  public void replace(BigDecimal available, BigDecimal reserved, BigDecimal consumed,
      BigDecimal minimumStock, Instant updatedAt) {
    this.available = available;
    this.reserved = reserved;
    this.consumed = consumed;
    this.minimumStock = minimumStock;
    this.updatedAt = updatedAt;
  }
}
