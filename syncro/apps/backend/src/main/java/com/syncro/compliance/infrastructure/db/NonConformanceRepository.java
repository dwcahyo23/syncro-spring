package com.syncro.compliance.infrastructure.db;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code non_conformances} (blueprint H1, story 15-2; queries for 21-1). */
public interface NonConformanceRepository extends JpaRepository<NonConformanceEntity, UUID> {

  Optional<NonConformanceEntity> findByNcNumber(String ncNumber);

  boolean existsByNcNumber(String ncNumber);

  /**
   * Scope-filtered list (story 21-1): NCs without a machine link are visible to any
   * authenticated user; machine-linked NCs follow the WorkOrderRepository.findScopedPage
   * predicate exactly — plant OR machine-group from the caller's derived scope (review
   * 21-1 P1: a plant-assigned manager without LEADER responsibility must still see
   * their plant's NCs). SUPER_ADMIN passes {@code unrestricted=true}. The {@code status}
   * filter uses the empty-string sentinel (Postgres 42P18 guard).
   */
  @Query("""
      select n from NonConformanceEntity n
      left join MachineEntity m on m.id = n.machineId
      where (:unrestricted = true or n.machineId is null
             or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
        and (:status = '' or n.status = cast(:status as string))
      order by n.createdAt desc
      """)
  List<NonConformanceEntity> findScoped(@Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds, @Param("groupIds") Collection<UUID> groupIds,
      @Param("status") String status);

  /**
   * Visibility twin of {@link #findScoped} for a single NC (detail reads and mutation
   * gates): counts the row only when it passes the same plant-OR-group predicate.
   */
  @Query("""
      select count(n) from NonConformanceEntity n
      left join MachineEntity m on m.id = n.machineId
      where n.id = :id
        and (:unrestricted = true or n.machineId is null
             or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
      """)
  long countVisible(@Param("id") UUID id, @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds, @Param("groupIds") Collection<UUID> groupIds);

  /** Plant + group of a machine — create-time scope gate and audit plantId resolution. */
  interface MachineScopeView {
    UUID getPlantId();

    UUID getGroupId();
  }

  @Query("select m.plant.id as plantId, m.machineGroup.id as groupId from MachineEntity m "
      + "where m.id = :machineId")
  Optional<MachineScopeView> findMachineScope(@Param("machineId") UUID machineId);

  /** Create-time reference validation: the workorder evidence link must resolve. */
  @Query("select count(w) from WorkOrderEntity w where w.id = :workOrderId")
  long countWorkOrder(@Param("workOrderId") String workOrderId);

  /** Create-time reference validation: the responsible user must exist. */
  @Query("select count(u) from AuthUserEntity u where u.id = :userId")
  long countUser(@Param("userId") UUID userId);
}
