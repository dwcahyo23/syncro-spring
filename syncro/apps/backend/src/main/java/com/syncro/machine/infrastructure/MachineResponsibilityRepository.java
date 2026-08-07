package com.syncro.machine.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MachineResponsibilityRepository extends JpaRepository<MachineResponsibilityEntity, UUID> {
    
    boolean existsByMachineIdAndUserId(UUID machineId, UUID userId);
    
    Page<MachineResponsibilityEntity> findByMachineId(UUID machineId, Pageable pageable);

    @Query("SELECT mr FROM MachineResponsibilityEntity mr " +
           "WHERE mr.machine.plant.id IN :plantIds")
    Page<MachineResponsibilityEntity> findAllByPlantIds(@Param("plantIds") List<UUID> plantIds, Pageable pageable);

    @Query("SELECT count(mr) FROM MachineResponsibilityEntity mr " +
           "WHERE mr.machine.plant.id IN :plantIds")
    long countByMachinePlantIdIn(@Param("plantIds") List<UUID> plantIds);
}
