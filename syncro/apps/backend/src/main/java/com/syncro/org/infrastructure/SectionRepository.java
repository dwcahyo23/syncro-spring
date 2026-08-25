package com.syncro.org.infrastructure;

import com.syncro.auth.infrastructure.PlantEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SectionRepository extends JpaRepository<SectionEntity, UUID> {

  @Query("""
      select section from SectionEntity section
      join fetch section.plant plant
      where plant.id = :plantId
        and (:activeOnly = false or section.active = true)
      order by section.code asc
      """)
  List<SectionEntity> findAllByPlantId(@Param("plantId") UUID plantId, @Param("activeOnly") boolean activeOnly);

  Optional<SectionEntity> findByPlantIdAndCodeIgnoreCase(UUID plantId, String code);

  Optional<SectionEntity> findByPlantIdAndNameIgnoreCase(UUID plantId, String name);

  boolean existsByPlantIdAndNameIgnoreCase(UUID plantId, String name);

  @Query("""
      select section from SectionEntity section
      join fetch section.plant plant
      where section.id = :id
      """)
  Optional<SectionEntity> findByIdWithPlant(@Param("id") UUID id);
}
