package com.syncro.inventory.domain;

/**
 * Inventory reservation state (blueprint E4, story 15-2): values match the
 * {@code inventory_reservations.status} CHECK constraint in V1 exactly.
 */
public enum InventoryReservationStatus {
  ACTIVE,
  CONSUMED,
  CANCELLED,
  EXPIRED
}
