package com.syncro.inventory.infrastructure.db;

import com.syncro.inventory.domain.InventoryTransferStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@code inventory_transfers} (blueprint E3, story 15-2; lifecycle
 * queries added by story 18-4). The table has no plant column — plant scoping joins
 * the source location (creation enforces source.plant == destination.plant, so the
 * source alone determines the transfer's plant).
 */
public interface InventoryTransferRepository extends JpaRepository<InventoryTransferEntity, UUID> {

  /**
   * Pessimistic row lock for review transitions (story 18-4): serializes concurrent
   * approve/reject on the same transfer so the PENDING_APPROVAL precondition cannot
   * pass twice (no {@code @Version} column exists on inventory_transfers). Pattern
   * anchor: {@code SparepartRepository.findByIdForUpdate} (story 18-1).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from InventoryTransferEntity t where t.id = :id")
  Optional<InventoryTransferEntity> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Plant-scoped list (story 18-4): optional sparepartId/status filters, newest
   * first. The plant is derived through the source location — never stored on the
   * transfer row.
   */
  @Query("""
      select t
      from InventoryTransferEntity t
      join InventoryLocationEntity l on l.id = t.sourceLocationId
      where l.plantId in :plantIds
        and (:sparepartId is null or t.sparepartId = :sparepartId)
        and (:status is null or t.status = :status)
      order by t.createdAt desc
      """)
  List<InventoryTransferEntity> findScoped(@Param("plantIds") Collection<UUID> plantIds,
      @Param("sparepartId") UUID sparepartId,
      @Param("status") InventoryTransferStatus status);

  /** Unscoped (SUPER_ADMIN) list with the same optional filters, newest first. */
  @Query("""
      select t
      from InventoryTransferEntity t
      where (:sparepartId is null or t.sparepartId = :sparepartId)
        and (:status is null or t.status = :status)
      order by t.createdAt desc
      """)
  List<InventoryTransferEntity> findAllFiltered(@Param("sparepartId") UUID sparepartId,
      @Param("status") InventoryTransferStatus status);
}
