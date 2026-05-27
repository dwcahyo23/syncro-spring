package com.syncro.masterdata.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MachineGroupRepository extends JpaRepository<MachineGroupEntity, UUID> {
  List<MachineGroupEntity> findByPlantIdOrderByNameAsc(UUID plantId);

  Optional<MachineGroupEntity> findByPlantIdAndNameIgnoreCase(UUID plantId, String name);

  boolean existsByPlantIdAndNameIgnoreCase(UUID plantId, String name);
}
