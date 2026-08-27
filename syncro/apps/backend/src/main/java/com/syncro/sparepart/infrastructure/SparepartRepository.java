package com.syncro.sparepart.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SparepartRepository extends JpaRepository<SparepartEntity, UUID> {
  Optional<SparepartEntity> findByCodeIgnoreCase(String code);

  Optional<SparepartEntity> findByMaterialCodeIgnoreCase(String materialCode);

  boolean existsByCodeIgnoreCase(String code);

  boolean existsByMaterialCodeIgnoreCaseAndIdNot(String materialCode, UUID id);

  @Query("""
      select count(sparepart) from SparepartEntity sparepart
      where sparepart.machine.plant.id in :plantIds
      """)
  long countByMachinePlantIdIn(@Param("plantIds") List<UUID> plantIds);

  @Query("""
      select count(sparepart) > 0 from SparepartEntity sparepart
      where sparepart.machine.id = :machineId
        and sparepart.category.id = :categoryId
        and sparepart.brand.id = :brandId
        and sparepart.kind.id = :kindId
        and sparepart.type.id = :typeId
      """)
  boolean existsByIdentity(
      @Param("machineId") UUID machineId,
      @Param("categoryId") UUID categoryId,
      @Param("brandId") UUID brandId,
      @Param("kindId") UUID kindId,
      @Param("typeId") UUID typeId);

  @Query("""
      select count(sparepart) > 0 from SparepartEntity sparepart
      where sparepart.id <> :sparepartId
        and sparepart.machine.id = :machineId
        and sparepart.category.id = :categoryId
        and sparepart.brand.id = :brandId
        and sparepart.kind.id = :kindId
        and sparepart.type.id = :typeId
      """)
  boolean existsByIdentityExcludingId(
      @Param("sparepartId") UUID sparepartId,
      @Param("machineId") UUID machineId,
      @Param("categoryId") UUID categoryId,
      @Param("brandId") UUID brandId,
      @Param("kindId") UUID kindId,
      @Param("typeId") UUID typeId);

  /**
   * {@param prefix} MUST already be LIKE-escaped by the caller (backslash, % and underscore)
   * - the trailing wildcard is appended here. See SparepartService.nextBomCode.
   */
  @Query("""
      select sparepart.code from SparepartEntity sparepart
      where upper(sparepart.code) like concat(upper(:prefix), '%') escape '\\'
      """)
  List<String> findCodesByEscapedPrefix(@Param("prefix") String prefix);

  @Query(value = """
      select sparepart from SparepartEntity sparepart
      join fetch sparepart.machine machine
      join fetch machine.plant plant
      join fetch sparepart.category category
      join fetch sparepart.brand brand
      join fetch sparepart.kind kind
      join fetch sparepart.type type
      where (:categoryId is null or category.id = :categoryId)
        and (:brandId is null or brand.id = :brandId)
        and (:kindId is null or kind.id = :kindId)
        and (:typeId is null or type.id = :typeId)
        and (:machineId is null or machine.id = :machineId)
        and (:machineCode = '' or lower(machine.code) like concat('%', :machineCode, '%') escape '\\')
        and (:search = '' or lower(sparepart.code) like concat('%', :search, '%') escape '\\'
          or lower(category.name) like concat('%', :search, '%') escape '\\'
          or lower(kind.name) like concat('%', :search, '%') escape '\\'
          or lower(brand.name) like concat('%', :search, '%') escape '\\'
          or lower(type.name) like concat('%', :search, '%') escape '\\')
      """,
      countQuery = """
          select count(sparepart) from SparepartEntity sparepart
          join sparepart.machine machine
          join sparepart.category category
          join sparepart.brand brand
          join sparepart.kind kind
          join sparepart.type type
          where (:categoryId is null or category.id = :categoryId)
            and (:brandId is null or brand.id = :brandId)
            and (:kindId is null or kind.id = :kindId)
            and (:typeId is null or type.id = :typeId)
            and (:machineId is null or machine.id = :machineId)
            and (:machineCode = '' or lower(machine.code) like concat('%', :machineCode, '%') escape '\\')
            and (:search = '' or lower(sparepart.code) like concat('%', :search, '%') escape '\\'
              or lower(category.name) like concat('%', :search, '%') escape '\\'
              or lower(kind.name) like concat('%', :search, '%') escape '\\'
              or lower(brand.name) like concat('%', :search, '%') escape '\\'
              or lower(type.name) like concat('%', :search, '%') escape '\\')
          """)
  Page<SparepartEntity> search(
      @Param("categoryId") UUID categoryId,
      @Param("brandId") UUID brandId,
      @Param("kindId") UUID kindId,
      @Param("typeId") UUID typeId,
      @Param("machineId") UUID machineId,
      @Param("search") String search,
      @Param("machineCode") String machineCode,
      Pageable pageable);
}
