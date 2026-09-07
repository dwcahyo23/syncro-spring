package com.syncro.compliance.infrastructure.db;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@code equipment_change_notices} (blueprint H4, story 15-2;
 * scope queries for 21-2). ECN scope follows the 21-1 {@code findScoped}
 * predicate minus the null-machine clause — {@code machine_id} is NOT NULL, so
 * every ECN is visible exactly when its machine's plant OR machine-group is in
 * the caller's derived scope (WorkOrderRepository.findScopedPage shape).
 * SUPER_ADMIN passes {@code unrestricted=true}.
 */
public interface EquipmentChangeNoticeRepository
    extends JpaRepository<EquipmentChangeNoticeEntity, UUID> {

  Optional<EquipmentChangeNoticeEntity> findByEcnNumber(String ecnNumber);

  boolean existsByEcnNumber(String ecnNumber);

  /** Scope-filtered list with an optional status filter (empty-string sentinel). */
  @Query("""
      select e from EquipmentChangeNoticeEntity e
      left join MachineEntity m on m.id = e.machineId
      where (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
        and (:status = '' or e.status = cast(:status as string))
      order by e.createdAt desc
      """)
  List<EquipmentChangeNoticeEntity> findScoped(@Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds, @Param("groupIds") Collection<UUID> groupIds,
      @Param("status") String status);

  /** Visibility twin of {@link #findScoped} for a single ECN (detail/mutation gate). */
  @Query("""
      select count(e) from EquipmentChangeNoticeEntity e
      left join MachineEntity m on m.id = e.machineId
      where e.id = :id
        and (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
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

  /** Execute-time reference validation: the implementing workorder must resolve. */
  @Query("select count(w) from WorkOrderEntity w where w.id = :workOrderId")
  long countWorkOrder(@Param("workOrderId") String workOrderId);

  /**
   * Plant of the machine a workorder is bound to (review 21-2 M6): the executed
   * workorder must be evidence for the ECN's own machine's plant, not an
   * unrelated cross-plant row.
   */
  @Query("select m.plant.id from WorkOrderEntity w join MachineEntity m on m.id = w.machineId "
      + "where w.id = :workOrderId")
  Optional<UUID> findWorkOrderMachinePlant(@Param("workOrderId") String workOrderId);
}
