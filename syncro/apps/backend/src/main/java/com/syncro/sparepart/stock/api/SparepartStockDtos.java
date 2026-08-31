package com.syncro.sparepart.stock.api;

import com.syncro.inventory.domain.InventoryStockBalance;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read/write models for the sparepart-stock API. Story 15-1 re-keys the storage to
 * {@code inventory_stock_balances} (sparepart_id + location_id); the operator-facing
 * shape stays material-code + plant, with the stock value columns renamed to the
 * blueprint E2 semantics (available/reserved/consumed/minimumStock).
 */
public final class SparepartStockDtos {
  private SparepartStockDtos() {
  }

  /** Stock balance read model (FR-146 remapped to blueprint E2 columns). */
  public record SparepartStockView(
      UUID balanceId,
      UUID sparepartId,
      UUID locationId,
      String materialCode,
      UUID plantId,
      BigDecimal available,
      BigDecimal reserved,
      BigDecimal consumed,
      BigDecimal minimumStock,
      long version,
      Instant createdAt,
      Instant updatedAt,
      boolean reorderWarning) {

    public static SparepartStockView from(InventoryStockBalance balance, String materialCode,
        UUID plantId) {
      return new SparepartStockView(balance.id(), balance.sparepartId(), balance.locationId(),
          materialCode, plantId, balance.available(), balance.reserved(), balance.consumed(),
          balance.minimumStock(), balance.version(), balance.createdAt(), balance.updatedAt(),
          balance.reorderWarning());
    }
  }

  public record SparepartStockListView(List<SparepartStockView> items) {
  }

  /** POST /api/v1/sparepart-stock — create (upsert semantics, version starts at 0). */
  public record CreateSparepartStockRequest(
      @NotBlank @Size(max = 64) String materialCode,
      @NotNull UUID plantId,
      @NotNull @Digits(integer = 16, fraction = 2) BigDecimal available,
      @NotNull @Digits(integer = 16, fraction = 2) BigDecimal reserved,
      @NotNull @Digits(integer = 16, fraction = 2) BigDecimal consumed,
      @NotNull @Digits(integer = 16, fraction = 2) BigDecimal minimumStock) {
  }

  /**
   * PUT /api/v1/sparepart-stock/{materialCode} — partial overwrite: all four value
   * fields are optional but at least one is required; the version is mandatory for
   * optimistic locking (409 VERSION_CONFLICT on mismatch).
   */
  public record UpdateSparepartStockRequest(
      @NotNull UUID plantId,
      @NotNull Long version,
      @Digits(integer = 16, fraction = 2) BigDecimal available,
      @Digits(integer = 16, fraction = 2) BigDecimal reserved,
      @Digits(integer = 16, fraction = 2) BigDecimal consumed,
      @Digits(integer = 16, fraction = 2) BigDecimal minimumStock) {
  }

  /** POST /api/v1/sparepart-stock/{materialCode}/adjust — signed delta (FR-146). */
  public record AdjustSparepartStockRequest(
      @NotNull UUID plantId,
      @NotNull @Digits(integer = 16, fraction = 2) BigDecimal delta) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }
}
