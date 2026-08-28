package com.syncro.sparepart.stock.infrastructure.db;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SparepartStockRepository extends JpaRepository<SparepartStockEntity, SparepartStockId> {

  Optional<SparepartStockEntity> findById(SparepartStockId id);

  /**
   * Atomic conditional decrement (AD-11, NFR-P2-6): subtracts {@code delta} from
   * stock_on_hand and bumps the version, but only when the result stays non-negative.
   * Returns the number of rows updated — 0 means the guard failed and the caller must
   * reject with NEGATIVE_STOCK_REJECTED (409). The single UPDATE statement is atomic
   * under MVCC, so concurrent decrements can never drive stock negative.
   */
  @Modifying(clearAutomatically = true)
  @Query("""
      update SparepartStockEntity s
      set s.stockOnHand = s.stockOnHand + :delta,
          s.version = s.version + 1,
          s.updatedAt = :now
      where s.materialCode = :materialCode
        and s.plantId = :plantId
        and s.stockOnHand + :delta >= 0
      """)
  int decrementIfSufficient(
      @Param("materialCode") String materialCode,
      @Param("plantId") UUID plantId,
      @Param("delta") BigDecimal delta,
      @Param("now") java.time.Instant now);

  /**
   * Atomic conditional adjust (AD-11, NFR-P2-6): same guard as {@link #decrementIfSufficient}
   * but accepts a signed delta so stock can also be corrected upward. Returns rows updated.
   */
  @Modifying(clearAutomatically = true)
  @Query("""
      update SparepartStockEntity s
      set s.stockOnHand = s.stockOnHand + :delta,
          s.version = s.version + 1,
          s.updatedAt = :now
      where s.materialCode = :materialCode
        and s.plantId = :plantId
        and s.stockOnHand + :delta >= 0
      """)
  int adjustIfSufficient(
      @Param("materialCode") String materialCode,
      @Param("plantId") UUID plantId,
      @Param("delta") BigDecimal delta,
      @Param("now") java.time.Instant now);

  /**
   * Reorder-warning rows (FR-146): every stock row where stock_on_hand &le; order_point,
   * scoped by plant. The rule is recomputed per query — it is a derived signal, never a
   * persisted flag, so it cannot go stale.
   */
  @Query("""
      select s
      from SparepartStockEntity s
      where s.plantId = :plantId
        and s.stockOnHand <= s.orderPoint
      order by s.materialCode asc
      """)
  List<SparepartStockEntity> findReorderWarnings(@Param("plantId") UUID plantId);

  @Query("""
      select s
      from SparepartStockEntity s
      where s.plantId = :plantId
      order by s.materialCode asc
      """)
  List<SparepartStockEntity> findAllByPlantId(@Param("plantId") UUID plantId);

  @Query("""
      select s
      from SparepartStockEntity s
      where (:plantId is null or s.plantId = :plantId)
      order by s.plantId asc, s.materialCode asc
      """)
  List<SparepartStockEntity> findAllScoped(@Param("plantId") UUID plantId);
}
