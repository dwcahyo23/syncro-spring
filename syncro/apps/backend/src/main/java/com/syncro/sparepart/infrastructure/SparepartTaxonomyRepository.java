package com.syncro.sparepart.infrastructure;

import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SparepartTaxonomyRepository extends JpaRepository<SparepartTaxonomyEntity, UUID> {
  List<SparepartTaxonomyEntity> findAllByOrderByDimensionAscNameAsc();

  List<SparepartTaxonomyEntity> findByDimensionOrderByNameAsc(SparepartTaxonomyDimension dimension);

  Optional<SparepartTaxonomyEntity> findByDimensionAndNameIgnoreCase(SparepartTaxonomyDimension dimension, String name);

  boolean existsByDimensionAndNameIgnoreCase(SparepartTaxonomyDimension dimension, String name);
}
