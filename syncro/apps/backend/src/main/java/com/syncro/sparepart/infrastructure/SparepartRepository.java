package com.syncro.sparepart.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SparepartRepository extends JpaRepository<SparepartEntity, UUID> {
  Optional<SparepartEntity> findByCodeIgnoreCase(String code);

  Optional<SparepartEntity> findByNameIgnoreCase(String name);

  boolean existsByCodeIgnoreCase(String code);

  boolean existsByNameIgnoreCase(String name);

  @Query(value = """
      select sparepart from SparepartEntity sparepart
      join fetch sparepart.category category
      join fetch sparepart.brand brand
      join fetch sparepart.kind kind
      join fetch sparepart.type type
      where (:categoryId is null or category.id = :categoryId)
        and (:brandId is null or brand.id = :brandId)
        and (:kindId is null or kind.id = :kindId)
        and (:typeId is null or type.id = :typeId)
      order by sparepart.name asc, sparepart.code asc
      """,
      countQuery = """
          select count(sparepart) from SparepartEntity sparepart
          join sparepart.category category
          join sparepart.brand brand
          join sparepart.kind kind
          join sparepart.type type
          where (:categoryId is null or category.id = :categoryId)
            and (:brandId is null or brand.id = :brandId)
            and (:kindId is null or kind.id = :kindId)
            and (:typeId is null or type.id = :typeId)
          """)
  Page<SparepartEntity> search(
      @Param("categoryId") UUID categoryId,
      @Param("brandId") UUID brandId,
      @Param("kindId") UUID kindId,
      @Param("typeId") UUID typeId,
      Pageable pageable);

  @Query(value = """
      select sparepart from SparepartEntity sparepart
      join fetch sparepart.category category
      join fetch sparepart.brand brand
      join fetch sparepart.kind kind
      join fetch sparepart.type type
      where (:categoryId is null or category.id = :categoryId)
        and (:brandId is null or brand.id = :brandId)
        and (:kindId is null or kind.id = :kindId)
        and (:typeId is null or type.id = :typeId)
        and (lower(sparepart.code) like concat('%', :search, '%') escape '\\'
          or lower(sparepart.name) like concat('%', :search, '%') escape '\\')
      order by sparepart.name asc, sparepart.code asc
      """,
      countQuery = """
          select count(sparepart) from SparepartEntity sparepart
          join sparepart.category category
          join sparepart.brand brand
          join sparepart.kind kind
          join sparepart.type type
          where (:categoryId is null or category.id = :categoryId)
            and (:brandId is null or brand.id = :brandId)
            and (:kindId is null or kind.id = :kindId)
            and (:typeId is null or type.id = :typeId)
            and (lower(sparepart.code) like concat('%', :search, '%') escape '\\'
              or lower(sparepart.name) like concat('%', :search, '%') escape '\\')
          """)
  Page<SparepartEntity> search(
      @Param("categoryId") UUID categoryId,
      @Param("brandId") UUID brandId,
      @Param("kindId") UUID kindId,
      @Param("typeId") UUID typeId,
      @Param("search") String search,
      Pageable pageable);
}
