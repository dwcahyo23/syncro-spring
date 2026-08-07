package com.syncro.sparepart.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MachineSparepartInstallationRepository extends JpaRepository<MachineSparepartInstallationEntity, UUID> {
  @Query("""
      select count(installation) from MachineSparepartInstallationEntity installation
      where installation.machine.plant.id in :plantIds
      """)
  long countByMachinePlantIdIn(@Param("plantIds") List<UUID> plantIds);

  @Query("""
      select installation from MachineSparepartInstallationEntity installation
      join fetch installation.machine machine
      join fetch machine.plant plant
      join fetch machine.machineGroup machineGroup
      join fetch installation.sparepart sparepart
      join fetch sparepart.category category
      join fetch sparepart.brand brand
      join fetch sparepart.kind kind
      join fetch sparepart.type type
      where (:machineId is null or machine.id = :machineId)
        and (:sparepartId is null or sparepart.id = :sparepartId)
        and (:plantId is null or plant.id = :plantId)
        and (:machineGroupId is null or machineGroup.id = :machineGroupId)
      """)
  Page<MachineSparepartInstallationEntity> findAllUnscoped(
      @Param("machineId") UUID machineId,
      @Param("sparepartId") UUID sparepartId,
      @Param("plantId") UUID plantId,
      @Param("machineGroupId") UUID machineGroupId,
      Pageable pageable);

  @Query("""
      select installation from MachineSparepartInstallationEntity installation
      join fetch installation.machine machine
      join fetch machine.plant plant
      join fetch machine.machineGroup machineGroup
      join fetch installation.sparepart sparepart
      join fetch sparepart.category category
      join fetch sparepart.brand brand
      join fetch sparepart.kind kind
      join fetch sparepart.type type
      where plant.id in :plantIds
        and (:machineId is null or machine.id = :machineId)
        and (:sparepartId is null or sparepart.id = :sparepartId)
        and (:plantId is null or plant.id = :plantId)
        and (:machineGroupId is null or machineGroup.id = :machineGroupId)
      """)
  Page<MachineSparepartInstallationEntity> findAllScoped(
      @Param("plantIds") List<UUID> plantIds,
      @Param("machineId") UUID machineId,
      @Param("sparepartId") UUID sparepartId,
      @Param("plantId") UUID plantId,
      @Param("machineGroupId") UUID machineGroupId,
      Pageable pageable);

  @Query("""
      select installation from MachineSparepartInstallationEntity installation
      join fetch installation.machine machine
      join fetch machine.plant
      join fetch machine.machineGroup
      join fetch installation.sparepart sparepart
      join fetch sparepart.category
      join fetch sparepart.brand
      join fetch sparepart.kind
      join fetch sparepart.type
      where installation.id = :id
      """)
  Optional<MachineSparepartInstallationEntity> findByIdWithDetails(@Param("id") UUID id);
}
