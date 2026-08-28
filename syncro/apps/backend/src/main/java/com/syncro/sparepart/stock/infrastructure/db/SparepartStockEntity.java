package com.syncro.sparepart.stock.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code sparepart_stock} row (story 12-4, FR-146/AD-11). The composite key
 * {@code (material_code, plant_id)} is materialized via an {@link IdClass}; the
 * {@code version} column is the JPA optimistic-lock counter. Negative stock is guarded
 * at the repository layer by the atomic conditional update, never by a DB CHECK.
 */
@Entity
@Table(name = "sparepart_stock")
@IdClass(SparepartStockId.class)
public class SparepartStockEntity {

  @Id
  @Column(name = "material_code", nullable = false, length = 64)
  private String materialCode;

  @Id
  @Column(name = "plant_id", nullable = false)
  private UUID plantId;

  @Column(name = "stock_on_hand", nullable = false, precision = 18, scale = 2)
  private BigDecimal stockOnHand;

  @Column(name = "order_point", nullable = false, precision = 18, scale = 2)
  private BigDecimal orderPoint;

  @Column(name = "order_qty", nullable = false, precision = 18, scale = 2)
  private BigDecimal orderQty;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SparepartStockEntity() {
  }

  public SparepartStockEntity(String materialCode, UUID plantId, BigDecimal stockOnHand,
      BigDecimal orderPoint, BigDecimal orderQty, Instant createdAt, Instant updatedAt) {
    this.materialCode = materialCode;
    this.plantId = plantId;
    this.stockOnHand = stockOnHand;
    this.orderPoint = orderPoint;
    this.orderQty = orderQty;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public UUID getPlantId() {
    return plantId;
  }

  public BigDecimal getStockOnHand() {
    return stockOnHand;
  }

  public BigDecimal getOrderPoint() {
    return orderPoint;
  }

  public BigDecimal getOrderQty() {
    return orderQty;
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

  /** Full overwrite of the provided fields (PUT semantics, story 12-4). */
  public void replace(BigDecimal stockOnHand, BigDecimal orderPoint, BigDecimal orderQty, Instant updatedAt) {
    this.stockOnHand = stockOnHand;
    this.orderPoint = orderPoint;
    this.orderQty = orderQty;
    this.updatedAt = updatedAt;
  }
}
