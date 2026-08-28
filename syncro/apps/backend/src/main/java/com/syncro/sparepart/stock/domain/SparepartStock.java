package com.syncro.sparepart.stock.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sparepart stock aggregate (FR-146, story 12-4). Stock is keyed by material code
 * (global one-code-one-sparepart, FR-078) per plant — unique (material_code, plant_id).
 * The reorder rule (stock_on_hand &le; order_point &rarr; recommend purchase of order_qty)
 * is a derived application-layer signal, never a persisted flag (AD-11).
 *
 * @param materialCode the sparepart material code (matches spareparts.material_code)
 * @param plantId      the plant this stock row belongs to
 * @param stockOnHand  current on-hand quantity
 * @param orderPoint   reorder point (OP)
 * @param orderQty     recommended purchase quantity (OQ)
 * @param version      optimistic-lock version (concurrent updates conflict → 409)
 * @param createdAt    row creation time
 * @param updatedAt    last mutation time
 */
public record SparepartStock(
    String materialCode,
    UUID plantId,
    BigDecimal stockOnHand,
    BigDecimal orderPoint,
    BigDecimal orderQty,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  /** Whether the reorder rule holds: on-hand is at or below the order point (FR-146). */
  public boolean reorderWarning() {
    return orderPoint != null && stockOnHand != null && stockOnHand.compareTo(orderPoint) <= 0;
  }
}
