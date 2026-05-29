package com.syncro.sparepart.infrastructure;

import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SparepartTaxonomyRepository extends JpaRepository<SparepartTaxonomyEntity, UUID> {
  List<SparepartTaxonomyEntity> findAllByOrderByDimensionAscNameAsc();

  List<SparepartTaxonomyEntity> findByDimensionOrderByNameAsc(SparepartTaxonomyDimension dimension);

  @Query("""
      select entry from SparepartTaxonomyEntity entry
      where entry.dimension = :dimension
        and (:categoryId is null or entry.category.id = :categoryId)
      order by entry.name asc
      """)
  List<SparepartTaxonomyEntity> findByDimensionAndCategoryIdOrderByNameAsc(SparepartTaxonomyDimension dimension, UUID categoryId);

  Optional<SparepartTaxonomyEntity> findByDimensionAndCodeIgnoreCase(SparepartTaxonomyDimension dimension, String code);

  Optional<SparepartTaxonomyEntity> findByDimensionAndNameIgnoreCase(SparepartTaxonomyDimension dimension, String name);

  boolean existsByDimensionAndCodeIgnoreCase(SparepartTaxonomyDimension dimension, String code);

  boolean existsByDimensionAndNameIgnoreCase(SparepartTaxonomyDimension dimension, String name);
}
