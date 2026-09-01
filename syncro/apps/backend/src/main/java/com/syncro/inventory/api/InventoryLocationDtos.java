package com.syncro.inventory.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request/response records for the inventory-locations API (story 18-2). camelCase
 * JSON; UUID ids are opaque. There is no delete request — deactivation is the
 * lifecycle end (PUT {@code isActive=false}).
 */
public final class InventoryLocationDtos {
  private InventoryLocationDtos() {
  }

  /** POST /api/v1/inventory-locations. */
  @Schema(description = "Create a named inventory location for a plant")
  public record CreateInventoryLocationRequest(
      @NotNull UUID plantId,
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 255) String name,
      @Size(max = 1000) String description) {
  }

  /**
   * PUT /api/v1/inventory-locations/{id} — replaceable fields; an absent
   * code/name/description keeps its current value and an absent {@code isActive}
   * keeps the current flag, so a deactivate-only body is just
   * {@code {"isActive": false}} (story 18-2 matrix). Provided code/name must not be
   * blank.
   */
  @Schema(description = "Update an inventory location (rename / description / activate-deactivate)")
  public record UpdateInventoryLocationRequest(
      @Size(min = 1, max = 64) String code,
      @Size(min = 1, max = 255) String name,
      @Size(max = 1000) String description,
      Boolean isActive) {
  }

  /** Location read model. */
  public record InventoryLocationView(
      UUID id,
      UUID plantId,
      String code,
      String name,
      String description,
      boolean isActive,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record InventoryLocationListView(List<InventoryLocationView> items) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors,
      String timestamp, String traceId) {
  }
}
