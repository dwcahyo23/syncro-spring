package com.syncro.org.infrastructure;

import com.syncro.auth.infrastructure.PlantEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepartmentRepository extends JpaRepository<DepartmentEntity, UUID> {

  @Query("""
      select d from DepartmentEntity d
      join fetch d.plant plant
      where plant.id = :plantId
        and (:activeOnly = false or d.active = true)
      order by d.name asc
      """)
  List<DepartmentEntity> findAllByPlantId(@Param("plantId") UUID plantId, @Param("activeOnly") boolean activeOnly);

  @Query("""
      select d from DepartmentEntity d
      join fetch d.plant plant
      where d.id = :id
      """)
  Optional<DepartmentEntity> findByIdWithPlant(@Param("id") UUID id);

  Optional<DepartmentEntity> findByPlantIdAndNameIgnoreCase(UUID plantId, String name);

  boolean existsByPlantIdAndNameIgnoreCase(UUID plantId, String name);
}
