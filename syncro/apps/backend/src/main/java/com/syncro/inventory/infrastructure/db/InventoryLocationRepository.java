package com.syncro.inventory.infrastructure.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@code inventory_locations} (blueprint E1, story 15-1).
 */
public interface InventoryLocationRepository extends JpaRepository<InventoryLocationEntity, UUID> {

  Optional<InventoryLocationEntity> findByPlantIdAndCodeIgnoreCase(UUID plantId, String code);

  Optional<InventoryLocationEntity> findFirstByPlantIdAndActiveTrueOrderByNameAsc(UUID plantId);

  /** Case-insensitive duplicate pre-check for PUT (story 18-2), excluding the row itself. */
  boolean existsByPlantIdAndCodeIgnoreCaseAndIdNot(UUID plantId, String code, UUID id);

  /** Plant list ordered by name (story 18-2). */
  List<InventoryLocationEntity> findAllByPlantIdOrderByNameAsc(UUID plantId);

  /** Active-only plant list ordered by name (story 18-2, {@code activeOnly=true}). */
  List<InventoryLocationEntity> findAllByPlantIdAndActiveTrueOrderByNameAsc(UUID plantId);
}
