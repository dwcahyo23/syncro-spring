package com.syncro.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Inventory stock balance aggregate (blueprint E2, story 15-1). Keyed by
 * (sparepart_id, location_id) with available/reserved/consumed/minimum_stock
 * semantics. The reorder rule ({@code available <= minimum_stock}) is a derived
 * application-layer signal, never a persisted flag (AD-11, POC proposal §4).
 *
 * @param id           balance row id (UUID PK)
 * @param sparepartId  the sparepart this balance belongs to
 * @param locationId   the inventory location of the balance
 * @param available    currently free-to-pick quantity
 * @param reserved     quantity held for approved-but-uncollected requests
 * @param consumed     lifetime running usage total
 * @param minimumStock reorder threshold (signal: available &le; minimum_stock)
 * @param version      optimistic-lock version (concurrent updates conflict → 409)
 * @param createdAt    row creation time
 * @param updatedAt    last mutation time
 */
public record InventoryStockBalance(
    UUID id,
    UUID sparepartId,
    UUID locationId,
    BigDecimal available,
    BigDecimal reserved,
    BigDecimal consumed,
    BigDecimal minimumStock,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  /** Whether the reorder rule holds: available is at or below minimum_stock. */
  public boolean reorderWarning() {
    return minimumStock != null && available != null && available.compareTo(minimumStock) <= 0;
  }
}
