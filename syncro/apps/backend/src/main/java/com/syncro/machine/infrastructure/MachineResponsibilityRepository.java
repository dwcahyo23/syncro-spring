package com.syncro.machine.infrastructure;

import com.syncro.machine.domain.ResponsibilityLevel;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MachineResponsibilityRepository extends JpaRepository<MachineResponsibilityEntity, UUID> {
    
    boolean existsByMachineIdAndUserId(UUID machineId, UUID userId);

    boolean existsByUserIdAndResponsibilityLevelIn(UUID userId, Collection<ResponsibilityLevel> levels);

    Optional<MachineResponsibilityEntity> findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(UUID machineId, ResponsibilityLevel responsibilityLevel);
    
    Page<MachineResponsibilityEntity> findByMachineId(UUID machineId, Pageable pageable);

    @Query("SELECT mr FROM MachineResponsibilityEntity mr " +
           "WHERE mr.machine.plant.id IN :plantIds")
    Page<MachineResponsibilityEntity> findAllByPlantIds(@Param("plantIds") List<UUID> plantIds, Pageable pageable);

    @Query("SELECT count(mr) FROM MachineResponsibilityEntity mr " +
           "WHERE mr.machine.plant.id IN :plantIds")
    long countByMachinePlantIdIn(@Param("plantIds") List<UUID> plantIds);

    @Query("""
        select distinct mr.machine.machineGroup.id
        from MachineResponsibilityEntity mr
        where mr.userId = :userId
          and mr.responsibilityLevel in :levels
        """)
    Set<UUID> findDistinctMachineGroupIdsByUserIdAndLevelIn(
        @Param("userId") UUID userId, @Param("levels") Collection<ResponsibilityLevel> levels);

    @Query("""
        select mr from MachineResponsibilityEntity mr
        where mr.machineId in :machineIds
          and mr.userId = :userId
          and mr.responsibilityLevel = :level
        """)
    List<MachineResponsibilityEntity> findAssignments(
        @Param("machineIds") Collection<UUID> machineIds,
        @Param("userId") UUID userId,
        @Param("level") ResponsibilityLevel level);

    void deleteByMachineIdInAndUserId(Collection<UUID> machineIds, UUID userId);
}
