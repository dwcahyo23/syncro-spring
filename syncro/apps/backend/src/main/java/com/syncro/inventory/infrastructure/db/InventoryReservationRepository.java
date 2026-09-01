package com.syncro.inventory.infrastructure.db;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code inventory_reservations} (blueprint E4, story 15-2). */
public interface InventoryReservationRepository extends JpaRepository<InventoryReservationEntity, UUID> {
}
