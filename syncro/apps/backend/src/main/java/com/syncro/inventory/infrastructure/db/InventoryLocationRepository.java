package com.syncro.inventory.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@code inventory_locations} (blueprint E1, story 15-1).
 */
public interface InventoryLocationRepository extends JpaRepository<InventoryLocationEntity, UUID> {

  Optional<InventoryLocationEntity> findByPlantIdAndCodeIgnoreCase(UUID plantId, String code);

  Optional<InventoryLocationEntity> findFirstByPlantIdAndActiveTrueOrderByNameAsc(UUID plantId);
}
