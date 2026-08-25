package com.syncro.alert.infrastructure;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.domain.SparepartAlertType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SparepartAlertRepository extends JpaRepository<SparepartAlertEntity, UUID> {

  boolean existsByMachineSparepartInstallationIdAndThresholdPercentageAndStatusNot(
      UUID installationId, int thresholdPercentage, SparepartAlertStatus excludedStatus);

  boolean existsByMachineSparepartInstallationIdAndAlertTypeAndStatusNot(
      UUID installationId, SparepartAlertType alertType, SparepartAlertStatus excludedStatus);

  @Query("""
      select alert from SparepartAlertEntity alert
      join fetch alert.installation installation
      join fetch installation.machine machine
      join fetch machine.plant plant
      join fetch machine.machineGroup machineGroup
      join fetch installation.sparepart sparepart
      where (:machineId is null or machine.id = :machineId)
        and (:plantId is null or plant.id = :plantId)
        and (:status is null or alert.status = :status)
      order by alert.createdAt desc
      """)
  Page<SparepartAlertEntity> findAllUnscoped(
      @Param("machineId") UUID machineId,
      @Param("plantId") UUID plantId,
      @Param("status") SparepartAlertStatus status,
      Pageable pageable);

  @Query("""
      select alert from SparepartAlertEntity alert
      join fetch alert.installation installation
      join fetch installation.machine machine
      join fetch machine.plant plant
      join fetch machine.machineGroup machineGroup
      join fetch installation.sparepart sparepart
      where (
        (plant.id in :plantIds
         and (:leaderGroupIds is null or machineGroup.id in :leaderGroupIds))
        or (:teamGroupIds is not null and machineGroup.id in :teamGroupIds)
      )
        and (:machineId is null or machine.id = :machineId)
        and (:plantId is null or plant.id = :plantId)
        and (:status is null or alert.status = :status)
      order by alert.createdAt desc
      """)
  Page<SparepartAlertEntity> findAllScoped(
      @Param("plantIds") List<UUID> plantIds,
      @Param("leaderGroupIds") List<UUID> leaderGroupIds,
      @Param("teamGroupIds") List<UUID> teamGroupIds,
      @Param("machineId") UUID machineId,
      @Param("plantId") UUID plantId,
      @Param("status") SparepartAlertStatus status,
      Pageable pageable);

  @Query("""
      select alert from SparepartAlertEntity alert
      join fetch alert.installation installation
      join fetch installation.machine machine
      join fetch machine.plant plant
      join fetch machine.machineGroup machineGroup
      join fetch installation.sparepart sparepart
      where alert.id = :id
      """)
  Optional<SparepartAlertEntity> findByIdWithDetails(@Param("id") UUID id);

  @Query("""
      select alert from SparepartAlertEntity alert
      join fetch alert.installation installation
      join fetch installation.machine machine
      join fetch machine.plant plant
      join fetch machine.machineGroup machineGroup
      join fetch installation.sparepart sparepart
      where alert.id = :id
        and (
          (plant.id in :plantIds
           and (:leaderGroupIds is null or machineGroup.id in :leaderGroupIds))
          or (:teamGroupIds is not null and machineGroup.id in :teamGroupIds)
        )
      """)
  Optional<SparepartAlertEntity> findByIdWithDetailsScopedToPlants(
      @Param("id") UUID id,
      @Param("plantIds") List<UUID> plantIds,
      @Param("leaderGroupIds") List<UUID> leaderGroupIds,
      @Param("teamGroupIds") List<UUID> teamGroupIds);
}

