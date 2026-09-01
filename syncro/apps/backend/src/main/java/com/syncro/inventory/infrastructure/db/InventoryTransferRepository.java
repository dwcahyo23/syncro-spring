package com.syncro.inventory.infrastructure.db;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code inventory_transfers} (blueprint E3, story 15-2). */
public interface InventoryTransferRepository extends JpaRepository<InventoryTransferEntity, UUID> {
}
