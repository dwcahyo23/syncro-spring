package com.syncro.inventory.infrastructure.db;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@code inventory_stock_balances} (blueprint E2, story 15-1).
 * The atomic conditional updates keep every mutation under MVCC so concurrent
 * decrements can never drive stock negative (AD-11, NFR-P2-6).
 */
public interface InventoryStockBalanceRepository extends JpaRepository<InventoryStockBalanceEntity, UUID> {

  Optional<InventoryStockBalanceEntity> findBySparepartIdAndLocationId(UUID sparepartId, UUID locationId);

  boolean existsBySparepartIdAndLocationId(UUID sparepartId, UUID locationId);

  /**
   * Pessimistic row lock on one balance row (story 18-4). Runs a native SELECT
   * ... FOR UPDATE whose result is discarded — Postgres holds the row lock until
   * the transaction ends. No version conflict because no entity enters the
   * persistence context. The transfer approve locks both touched rows in
   * deterministic location-id order (smallest first) before any write, so two
   * opposing transfers (X→Y vs Y→X) cannot deadlock.
   */
  @Query(value = """
      select 1 from inventory_stock_balances
      where sparepart_id = :sparepartId and location_id = :locationId
      for update
      """, nativeQuery = true)
  void lockBySparepartAndLocation(
      @Param("sparepartId") UUID sparepartId,
      @Param("locationId") UUID locationId);

  /**
   * Atomic conditional availability decrement (AD-11, NFR-P2-6): subtracts
   * {@code delta} from {@code available}, adds the same amount to the lifetime
   * {@code consumed} running total, and bumps the version — but only when the
   * result stays non-negative. Returns rows updated — 0 means the guard failed
   * and the caller must reject with NEGATIVE_STOCK_REJECTED (409).
   */
  @Modifying(clearAutomatically = true)
  @Query("""
      update InventoryStockBalanceEntity s
      set s.available = s.available - :delta,
          s.consumed = s.consumed + :delta,
          s.version = s.version + 1,
          s.updatedAt = :now
      where s.sparepartId = :sparepartId
        and s.locationId = :locationId
        and s.available - :delta >= 0
      """)
  int consumeIfSufficient(
      @Param("sparepartId") UUID sparepartId,
      @Param("locationId") UUID locationId,
      @Param("delta") BigDecimal delta,
      @Param("now") java.time.Instant now);

  /**
   * Atomic conditional signed adjust of {@code available} (AD-11, NFR-P2-6): same
   * non-negative guard as {@link #consumeIfSufficient} but accepts a signed delta
   * so stock can also be corrected upward. The consumed running total is untouched
   * (corrections are not lifetime usage). Returns rows updated.
   */
  @Modifying(clearAutomatically = true)
  @Query("""
      update InventoryStockBalanceEntity s
      set s.available = s.available + :delta,
          s.version = s.version + 1,
          s.updatedAt = :now
      where s.sparepartId = :sparepartId
        and s.locationId = :locationId
        and s.available + :delta >= 0
      """)
  int adjustAvailableIfSufficient(
      @Param("sparepartId") UUID sparepartId,
      @Param("locationId") UUID locationId,
      @Param("delta") BigDecimal delta,
      @Param("now") java.time.Instant now);

  /**
   * Atomic transfer credit (story 18-4): create-or-increment {@code available} at
   * (sparepart, location) in ONE statement. A missing destination row is inserted
   * with {@code available = delta} (other stock columns take their DB defaults);
   * an existing row is incremented and its version bumped in SQL — the same
   * version-bypass idiom as the conditional updates, so the destination credit is
   * race-safe without a read-then-write. The conflict target is
   * {@code uq_inventory_stock_balances_sparepart_location}. Runs inside the approve
   * transaction: a failure anywhere rolls the whole move back (no partial transfer).
   */
  @Modifying(clearAutomatically = true)
  @Query(value = """
      insert into inventory_stock_balances (id, sparepart_id, location_id, available, created_at, updated_at)
      values (:id, :sparepartId, :locationId, :delta, :now, :now)
      on conflict (sparepart_id, location_id) do update
      set available = inventory_stock_balances.available + excluded.available,
          version = inventory_stock_balances.version + 1,
          updated_at = excluded.updated_at
      """, nativeQuery = true)
  int creditTransferIn(
      @Param("id") UUID id,
      @Param("sparepartId") UUID sparepartId,
      @Param("locationId") UUID locationId,
      @Param("delta") BigDecimal delta,
      @Param("now") java.time.Instant now);

  /**
   * Reorder-warning rows (FR-146 remapped, POC proposal §4): every balance row where
   * available &le; minimum_stock, scoped by plant through the location. The rule is
   * recomputed per query — it is a derived signal, never a persisted flag, so it
   * cannot go stale.
   */
  @Query("""
      select s
      from InventoryStockBalanceEntity s
      join InventoryLocationEntity l on l.id = s.locationId
      where l.plantId = :plantId
        and s.available <= s.minimumStock
      order by s.sparepartId asc
      """)
  List<InventoryStockBalanceEntity> findReorderWarnings(@Param("plantId") UUID plantId);

  @Query("""
      select s
      from InventoryStockBalanceEntity s
      join InventoryLocationEntity l on l.id = s.locationId
      where l.plantId = :plantId
      order by s.sparepartId asc
      """)
  List<InventoryStockBalanceEntity> findAllByPlantId(@Param("plantId") UUID plantId);

  /** All balance rows at one location (story 18-3, location-scoped list). */
  List<InventoryStockBalanceEntity> findAllByLocationIdOrderBySparepartIdAsc(UUID locationId);

  /**
   * Reorder-warning rows at one location (story 18-3): same derived rule as
   * {@link #findReorderWarnings} (available &le; minimum_stock, never persisted),
   * narrowed to a single location id.
   */
  @Query("""
      select s
      from InventoryStockBalanceEntity s
      where s.locationId = :locationId
        and s.available <= s.minimumStock
      order by s.sparepartId asc
      """)
  List<InventoryStockBalanceEntity> findReorderWarningsByLocationId(@Param("locationId") UUID locationId);

  @Query("""
      select s
      from InventoryStockBalanceEntity s
      join InventoryLocationEntity l on l.id = s.locationId
      where (:plantId is null or l.plantId = :plantId)
      order by l.plantId asc, s.sparepartId asc
      """)
  List<InventoryStockBalanceEntity> findAllScoped(@Param("plantId") UUID plantId);
}
