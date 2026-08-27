package com.syncro.sparepart.request.infrastructure.db;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SparepartRequestRepository extends JpaRepository<SparepartRequestEntity, UUID> {

  List<SparepartRequestEntity> findByWorkOrderIdOrderByRequestedAtAsc(String workOrderId);

  Optional<SparepartRequestEntity> findById(UUID id);

  /**
   * Sparepart-request list read (story 12-1 list view): every request visible in the
   * derived scope (plant OR machine-group), joined to the machine so the plant/group
   * predicates work. Unbound CONSUMABLE requests (no machine, no workorder) carry no
   * scope and are visible only to unrestricted users (SUPER_ADMIN). Rows are ordered by
   * {@code requestedAt desc} for a stable page order. {@code unrestricted} (SUPER_ADMIN)
   * bypasses the scope filter.
   */
  @Query("""
      select r
      from SparepartRequestEntity r
      left join MachineEntity m on m.id = r.machineId
      where (:unrestricted = true
             or (m is null and r.machineId is null and :plantIds is empty)
             or m.plant.id in :plantIds
             or m.machineGroup.id in :groupIds)
      order by r.requestedAt desc
      """)
  List<SparepartRequestEntity> findScopedPage(
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds,
      Pageable pageable);

  /**
   * Matching-count twin of {@link #findScopedPage}: identical predicates so {@code total}
   * reflects the same filtered set.
   */
  @Query("""
      select count(r)
      from SparepartRequestEntity r
      left join MachineEntity m on m.id = r.machineId
      where (:unrestricted = true
             or (m is null and r.machineId is null and :plantIds is empty)
             or m.plant.id in :plantIds
             or m.machineGroup.id in :groupIds)
      """)
  long countScoped(
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds);
}
