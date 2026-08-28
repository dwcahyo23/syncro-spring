package com.syncro.sparepart.request.infrastructure.db;

import com.syncro.sparepart.request.domain.SparepartRequestStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SparepartRequestRepository extends JpaRepository<SparepartRequestEntity, UUID> {

  List<SparepartRequestEntity> findByWorkOrderIdOrderByRequestedAtAsc(String workOrderId);

  Optional<SparepartRequestEntity> findById(UUID id);

  /**
   * A single request row locked for update (PESSIMISTIC_WRITE) — serializes concurrent
   * transitions on the same request (story 12-2). Mirrors the workorder
   * {@code findByIdForUpdate} pattern (AD-5).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from SparepartRequestEntity r where r.id = :id")
  Optional<SparepartRequestEntity> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Readiness query for the real {@code SparepartRequestReadinessPort} (AD-5, story 12-2):
   * whether the workorder has at least one live non-READY request — any non-terminal
   * request whose status is not READY or CLOSED.
   */
  @Query("""
      select count(r) > 0
      from SparepartRequestEntity r
      where r.workOrderId = :workOrderId
        and r.status <> com.syncro.sparepart.request.domain.SparepartRequestStatus.READY
        and r.status <> com.syncro.sparepart.request.domain.SparepartRequestStatus.CLOSED
      """)
  boolean hasLiveNonReadyRequest(@Param("workOrderId") String workOrderId);

  /**
   * Escalation stale query (story 12-3, FR-147): requests whose status is in the given
   * step's status set and whose updated_at is older than the step cutoff. The escalation
   * worker evaluates this per run, so each step fires once when its duration elapses.
   */
  @Query("""
      select r
      from SparepartRequestEntity r
      where r.status in :statuses
        and r.updatedAt <= :cutoff
      """)
  List<SparepartRequestEntity> findStaleByStatusInAndUpdatedAtBefore(
      @Param("statuses") Collection<SparepartRequestStatus> statuses,
      @Param("cutoff") Instant cutoff);

  /**
   * Sparepart-request list read (story 12-1 list view): every request visible in the
   * derived scope (plant OR machine-group), joined to the machine so the plant/group
   * predicates work. Unbound CONSUMABLE requests (no machine, no workorder) carry no
   * scope and are visible only to unrestricted users (SUPER_ADMIN) or users with an
   * empty plant scope. Rows are ordered by {@code requestedAt desc} for a stable page
   * order. {@code unrestricted} (SUPER_ADMIN) bypasses the scope filter.
   */
  @Query("""
      select r
      from SparepartRequestEntity r
      left join MachineEntity m on m.id = r.machineId
      where (:unrestricted = true
             or (m is null and r.machineId is null and :hasPlantScope = false)
             or m.plant.id in :plantIds
             or m.machineGroup.id in :groupIds)
      order by r.requestedAt desc
      """)
  List<SparepartRequestEntity> findScopedPage(
      @Param("unrestricted") boolean unrestricted,
      @Param("hasPlantScope") boolean hasPlantScope,
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
             or (m is null and r.machineId is null and :hasPlantScope = false)
             or m.plant.id in :plantIds
             or m.machineGroup.id in :groupIds)
      """)
  long countScoped(
      @Param("unrestricted") boolean unrestricted,
      @Param("hasPlantScope") boolean hasPlantScope,
      @Param("plantIds") Collection<UUID> plantIds,
      @Param("groupIds") Collection<UUID> groupIds);
}
