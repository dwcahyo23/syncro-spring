package com.syncro.sparepart.stock.api;

import com.syncro.sparepart.stock.domain.SparepartStock;
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

public final class SparepartStockDtos {
  private SparepartStockDtos() {
  }

  /** Story 12-4: stock row read model (FR-146). */
  public record SparepartStockView(
      String materialCode,
      UUID plantId,
      BigDecimal stockOnHand,
      BigDecimal orderPoint,
      BigDecimal orderQty,
      long version,
      Instant createdAt,
      Instant updatedAt,
      boolean reorderWarning) {

    public static SparepartStockView from(SparepartStock stock) {
      return new SparepartStockView(stock.materialCode(), stock.plantId(), stock.stockOnHand(),
          stock.orderPoint(), stock.orderQty(), stock.version(), stock.createdAt(), stock.updatedAt(),
          stock.reorderWarning());
    }
  }

  public record SparepartStockListView(List<SparepartStockView> items) {
  }

  /** POST /api/v1/sparepart-stock — create (upsert semantics, version starts at 0). */
  public record CreateSparepartStockRequest(
      @NotBlank @Size(max = 64) String materialCode,
      @NotNull UUID plantId,
      @NotNull @Digits(integer = 16, fraction = 2) BigDecimal stockOnHand,
      @NotNull @Digits(integer = 16, fraction = 2) BigDecimal orderPoint,
      @NotNull @Digits(integer = 16, fraction = 2) BigDecimal orderQty) {
  }

  /**
   * PUT /api/v1/sparepart-stock/{materialCode} — partial overwrite: all three value
   * fields are optional but at least one is required; the version is mandatory for
   * optimistic locking (409 VERSION_CONFLICT on mismatch).
   */
  public record UpdateSparepartStockRequest(
      @NotNull UUID plantId,
      @NotNull Long version,
      @Digits(integer = 16, fraction = 2) BigDecimal stockOnHand,
      @Digits(integer = 16, fraction = 2) BigDecimal orderPoint,
      @Digits(integer = 16, fraction = 2) BigDecimal orderQty) {
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
