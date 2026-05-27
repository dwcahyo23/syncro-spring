package com.syncro.machine.infrastructure;

import com.syncro.machine.domain.MachineStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MachineRepository extends JpaRepository<MachineEntity, UUID> {
  @Query("""
      select machine from MachineEntity machine
      join fetch machine.plant plant
      join fetch machine.machineGroup machineGroup
      where (:plantIds is null or plant.id in :plantIds)
        and (:plantId is null or plant.id = :plantId)
        and (:machineGroupId is null or machineGroup.id = :machineGroupId)
        and (:status is null or machine.status = :status)
      order by plant.code asc, machine.code asc
      """)
  List<MachineEntity> findAllScoped(
      @Param("plantIds") List<UUID> plantIds,
      @Param("plantId") UUID plantId,
      @Param("machineGroupId") UUID machineGroupId,
      @Param("status") MachineStatus status);

  @Query("""
      select machine from MachineEntity machine
      join fetch machine.plant
      join fetch machine.machineGroup
      where machine.id = :id
      """)
  Optional<MachineEntity> findByIdWithPlantAndGroup(@Param("id") UUID id);

  Optional<MachineEntity> findByPlantIdAndCodeIgnoreCase(UUID plantId, String code);

  boolean existsByPlantIdAndCodeIgnoreCase(UUID plantId, String code);
}
