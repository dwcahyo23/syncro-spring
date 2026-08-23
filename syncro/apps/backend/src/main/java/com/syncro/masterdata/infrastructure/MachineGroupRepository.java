package com.syncro.masterdata.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MachineGroupRepository extends JpaRepository<MachineGroupEntity, UUID> {
  @Query("""
      select group from MachineGroupEntity group
      join fetch group.plant plant
      where plant.id = :plantId
        and (:search is null or lower(group.name) like :search escape '\\' or lower(plant.code) like :search escape '\\' or lower(plant.name) like :search escape '\\')
      """)
  Page<MachineGroupEntity> search(@Param("plantId") UUID plantId, @Param("search") String search, Pageable pageable);

  Optional<MachineGroupEntity> findByPlantIdAndNameIgnoreCase(UUID plantId, String name);

  boolean existsByPlantIdAndNameIgnoreCase(UUID plantId, String name);

  long countByPlantIdIn(List<UUID> plantIds);
}
