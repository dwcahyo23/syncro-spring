package com.syncro.auth.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlantRepository extends JpaRepository<PlantEntity, UUID> {
  boolean existsByCodeIgnoreCase(String code);

  Optional<PlantEntity> findByCodeIgnoreCase(String code);

  List<PlantEntity> findByIdIn(List<UUID> ids);

  long countByIdIn(List<UUID> ids);
}
