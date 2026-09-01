package com.syncro.inventory.domain;

/**
 * Inventory transfer approval state (blueprint E3, story 15-2): values match the
 * {@code inventory_transfers.status} CHECK constraint in V1 exactly.
 */
public enum InventoryTransferStatus {
  PENDING_APPROVAL,
  APPROVED,
  REJECTED
}
