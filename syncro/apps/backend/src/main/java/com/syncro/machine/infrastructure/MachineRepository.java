package com.syncro.machine.infrastructure;

import com.syncro.machine.domain.MachineStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MachineRepository extends JpaRepository<MachineEntity, UUID> {
  @Query("""
      select machine from MachineEntity machine
      join fetch machine.plant plant
      join fetch machine.machineGroup machineGroup
      where (:plantId is null or plant.id = :plantId)
        and (:machineGroupId is null or machineGroup.id = :machineGroupId)
        and (:status is null or machine.status = :status)
        and (:search is null or lower(machine.code) like :search escape '\\' or lower(machine.name) like :search escape '\\' or lower(plant.code) like :search escape '\\' or lower(plant.name) like :search escape '\\')
      """)
  Page<MachineEntity> findAllUnscoped(
      @Param("plantId") UUID plantId,
      @Param("machineGroupId") UUID machineGroupId,
      @Param("status") MachineStatus status,
      @Param("search") String search,
      Pageable pageable);

  @Query("""
      select machine from MachineEntity machine
      join fetch machine.plant plant
      join fetch machine.machineGroup machineGroup
      where plant.id in :plantIds
        and (:plantId is null or plant.id = :plantId)
        and (:machineGroupId is null or machineGroup.id = :machineGroupId)
        and (:machineGroupIds is null or machineGroup.id in :machineGroupIds)
        and (:status is null or machine.status = :status)
        and (:search is null or lower(machine.code) like :search escape '\\' or lower(machine.name) like :search escape '\\' or lower(plant.code) like :search escape '\\' or lower(plant.name) like :search escape '\\')
      """)
  Page<MachineEntity> findAllScoped(
      @Param("plantIds") List<UUID> plantIds,
      @Param("plantId") UUID plantId,
      @Param("machineGroupId") UUID machineGroupId,
      @Param("machineGroupIds") List<UUID> machineGroupIds,
      @Param("status") MachineStatus status,
      @Param("search") String search,
      Pageable pageable);

  @Query("""
      select machine from MachineEntity machine
      join fetch machine.plant
      join fetch machine.machineGroup
      where machine.id = :id
      """)
  Optional<MachineEntity> findByIdWithPlantAndGroup(@Param("id") UUID id);

  Optional<MachineEntity> findByPlantIdAndCodeIgnoreCase(UUID plantId, String code);

  @Query("""
      select machine from MachineEntity machine
      join fetch machine.plant
      join fetch machine.machineGroup
      where machine.plant.id = :plantId
        and lower(machine.code) = lower(:code)
      """)
  Optional<MachineEntity> findByPlantIdAndCodeIgnoreCaseWithPlantAndGroup(
      @Param("plantId") UUID plantId,
      @Param("code") String code);

  Optional<MachineEntity> findByCodeIgnoreCase(String code);

  boolean existsByPlantIdAndCodeIgnoreCase(UUID plantId, String code);

  long countByPlantIdIn(List<UUID> plantIds);

  @Query("""
      select count(distinct m) from MachineEntity m
      join MachineSparepartInstallationEntity i on i.machine = m
      join MachineResponsibilityEntity r on r.machineId = m.id
      where m.plant.id in :plantIds
      """)
  long countEligibleByPlantIdIn(@Param("plantIds") List<UUID> plantIds);
}
