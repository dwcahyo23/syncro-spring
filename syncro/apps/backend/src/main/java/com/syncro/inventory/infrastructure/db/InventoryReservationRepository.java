package com.syncro.inventory.infrastructure.db;

import com.syncro.inventory.domain.InventoryReservationStatus;
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
 * Persistence for {@code inventory_reservations} (blueprint E4, story 15-2). Lifecycle
 * queries added by story 18-5: list/filter reads, pessimistic lock for mutation paths.
 */
public interface InventoryReservationRepository extends JpaRepository<InventoryReservationEntity, UUID> {

  /**
   * Pessimistic row lock for reservation mutations (story 18-5): serializes concurrent
   * consume/cancel on the same row so the ACTIVE precondition cannot pass twice.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from InventoryReservationEntity r where r.id = :id")
  Optional<InventoryReservationEntity> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Plant-scoped list (story 18-5): optional sparepartId/locationId/status/referenceType/
   * referenceId filters, newest first. The plant is derived through the location.
   */
  @Query("""
      select r
      from InventoryReservationEntity r
      join InventoryLocationEntity l on l.id = r.locationId
      where l.plantId in :plantIds
        and (:sparepartId is null or r.sparepartId = :sparepartId)
        and (:locationId is null or r.locationId = :locationId)
        and (:status is null or r.status = :status)
        and (:referenceType is null or r.referenceType = :referenceType)
        and (:referenceId is null or r.referenceId = :referenceId)
      order by r.createdAt desc
      """)
  List<InventoryReservationEntity> findScoped(@Param("plantIds") Collection<UUID> plantIds,
      @Param("sparepartId") UUID sparepartId,
      @Param("locationId") UUID locationId,
      @Param("status") InventoryReservationStatus status,
      @Param("referenceType") String referenceType,
      @Param("referenceId") String referenceId);

  /** Unscoped (SUPER_ADMIN) list with the same optional filters, newest first. */
  @Query("""
      select r
      from InventoryReservationEntity r
      where (:sparepartId is null or r.sparepartId = :sparepartId)
        and (:locationId is null or r.locationId = :locationId)
        and (:status is null or r.status = :status)
        and (:referenceType is null or r.referenceType = :referenceType)
        and (:referenceId is null or r.referenceId = :referenceId)
      order by r.createdAt desc
      """)
  List<InventoryReservationEntity> findAllFiltered(@Param("sparepartId") UUID sparepartId,
      @Param("locationId") UUID locationId,
      @Param("status") InventoryReservationStatus status,
      @Param("referenceType") String referenceType,
      @Param("referenceId") String referenceId);
}
