package com.syncro.org.infrastructure.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code machine_areas} (blueprint A11, story 15-2). */
public interface MachineAreaRepository extends JpaRepository<MachineAreaEntity, UUID> {

  @Query("""
      select a from MachineAreaEntity a
      where a.plantId = :plantId
        and (:activeOnly = false or a.active = true)
      order by a.name asc
      """)
  List<MachineAreaEntity> findAllByPlantId(@Param("plantId") UUID plantId, @Param("activeOnly") boolean activeOnly);

  Optional<MachineAreaEntity> findByPlantIdAndNameIgnoreCase(UUID plantId, String name);

  Optional<MachineAreaEntity> findByPlantIdAndCodeIgnoreCase(UUID plantId, String code);
}
