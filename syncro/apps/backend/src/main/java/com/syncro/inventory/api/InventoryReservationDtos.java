package com.syncro.inventory.api;

import com.syncro.inventory.domain.InventoryReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request/response records for the inventory-reservations API (story 18-5, blueprint
 * E4). camelCase JSON; UUID ids are opaque. There is no update or delete request — a
 * reservation is created ACTIVE and ends in CONSUMED/CANCELLED/EXPIRED (terminal
 * states only).
 */
public final class InventoryReservationDtos {
  private InventoryReservationDtos() {
  }

  /**
   * POST /api/v1/inventory-reservations. {@code requested_by} is never part of the
   * body; {@code expiresAt} is optional (null = no expiry). referenceType/referenceId
   * are free-form uppercase-string references (WORK_ORDER first) — no FK by design.
   */
  @Schema(description = "Reserve stock at one location against a reference (workorder)")
  public record CreateInventoryReservationRequest(
      @NotNull UUID sparepartId,
      @NotNull UUID locationId,
      @NotNull @Positive @Digits(integer = 16, fraction = 2) BigDecimal quantity,
      @NotBlank @Size(max = 50) String referenceType,
      @NotBlank @Size(max = 64) String referenceId,
      Instant expiresAt) {
  }

  /** POST /api/v1/inventory-reservations/{id}/consume — partial or full draw-down. */
  @Schema(description = "Consume part of a reservation (remaining draws down, reserved releases)")
  public record ConsumeInventoryReservationRequest(
      @NotNull @Positive @Digits(integer = 16, fraction = 2) BigDecimal quantity) {
  }

  /** Reservation read model. */
  public record InventoryReservationView(
      UUID id,
      UUID sparepartId,
      UUID locationId,
      BigDecimal quantity,
      BigDecimal remainingQuantity,
      InventoryReservationStatus status,
      String referenceType,
      String referenceId,
      UUID requestedBy,
      UUID consumedBy,
      UUID cancelledBy,
      Instant expiresAt,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record InventoryReservationListView(List<InventoryReservationView> items) {
  }

  /** Stable error shape shared by the inventory API surface. */
  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors,
      String timestamp, String traceId) {
  }
}
