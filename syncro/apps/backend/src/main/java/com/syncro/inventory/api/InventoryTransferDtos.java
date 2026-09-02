package com.syncro.inventory.api;

import com.syncro.inventory.domain.InventoryTransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request/response records for the inventory-transfers API (story 18-4, blueprint
 * E3). camelCase JSON; UUID ids are opaque. There is no update request — a transfer
 * is created PENDING_APPROVAL and ends in APPROVED/REJECTED (terminal states only,
 * no delete endpoint).
 */
public final class InventoryTransferDtos {
  private InventoryTransferDtos() {
  }

  /** POST /api/v1/inventory-transfers. requested_by is never part of the body. */
  @Schema(description = "Request a stock transfer between two locations of the same plant")
  public record CreateInventoryTransferRequest(
      @NotNull UUID sparepartId,
      @NotNull UUID sourceLocationId,
      @NotNull UUID destinationLocationId,
      @NotNull @Positive @Digits(integer = 16, fraction = 2) BigDecimal quantity) {
  }

  /** POST /api/v1/inventory-transfers/{id}/reject — reason is mandatory. */
  @Schema(description = "Reject a pending transfer with a reason")
  public record RejectInventoryTransferRequest(
      @NotBlank @Size(max = 2000) String rejectionReason) {
  }

  /** Transfer read model. */
  public record InventoryTransferView(
      UUID id,
      UUID sparepartId,
      UUID sourceLocationId,
      UUID destinationLocationId,
      BigDecimal quantity,
      InventoryTransferStatus status,
      UUID requestedBy,
      UUID reviewedBy,
      String rejectionReason,
      Instant reviewedAt,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record InventoryTransferListView(List<InventoryTransferView> items) {
  }
}
