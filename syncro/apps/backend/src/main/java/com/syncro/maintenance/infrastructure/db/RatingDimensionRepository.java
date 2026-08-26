package com.syncro.maintenance.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Rating dimension persistence (story 10-8). Dimensions are configuration data managed
 * by SUPER_ADMIN; reads are available to any authenticated user.
 */
public interface RatingDimensionRepository extends JpaRepository<RatingDimensionEntity, UUID> {

  List<RatingDimensionEntity> findAllByOrderBySortOrderAsc();

  java.util.Optional<RatingDimensionEntity> findByCode(String code);
}