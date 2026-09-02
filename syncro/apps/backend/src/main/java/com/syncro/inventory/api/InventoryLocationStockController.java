package com.syncro.inventory.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.inventory.api.InventoryLocationDtos.AdjustLocationStockRequest;
import com.syncro.inventory.api.InventoryLocationDtos.CreateLocationStockRequest;
import com.syncro.inventory.application.InventoryStockService;
import com.syncro.inventory.application.InventoryStockService.LocationUpsertCommand;
import com.syncro.inventory.domain.InventoryStockBalance;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.stock.api.SparepartStockDtos.SparepartStockListView;
import com.syncro.sparepart.stock.api.SparepartStockDtos.SparepartStockView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Location-scoped stock-balances API (story 18-3). Where {@code /sparepart-stock}
 * addresses stock by material code + plant (with an optional locationId), this
 * surface addresses it by location directly: the plant is derived from the location,
 * never taken from the request body — that is what makes a cross-plant mutation
 * impossible here. Mutations are gated INVENTORY_MAINTENANCE/STOREKEEPER/SUPER_ADMIN
 * with the location's plant in the caller's assignment scope (service-side); the
 * response reuses the shared {@link SparepartStockView} shape so both surfaces agree.
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory-locations/{locationId}/stock-balances")
public class InventoryLocationStockController {

  private final InventoryStockService service;
  private final SparepartRepository spareparts;
  private final InventoryLocationRepository locations;

  public InventoryLocationStockController(InventoryStockService service,
      SparepartRepository spareparts, InventoryLocationRepository locations) {
    this.service = service;
    this.spareparts = spareparts;
    this.locations = locations;
  }

  @Operation(operationId = "listInventoryLocationStockBalances",
      summary = "List stock balances at one inventory location")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Stock balances returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "INVENTORY_LOCATION_NOT_FOUND")
  })
  @GetMapping
  public SparepartStockListView list(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID locationId) {
    var items = service.listAtLocation(user, locationId).stream()
        .map(this::toView)
        .toList();
    return new SparepartStockListView(items);
  }

  @Operation(operationId = "createInventoryLocationStockBalance",
      summary = "Create (or upsert) a stock balance for a material code at one location")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Stock balance created",
          content = @Content(schema = @Schema(implementation = SparepartStockView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Sparepart or location not found")
  })
  @PostMapping
  public ResponseEntity<SparepartStockView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID locationId, @Valid @RequestBody CreateLocationStockRequest request) {
    var command = new LocationUpsertCommand(request.materialCode(), request.available(),
        request.reserved(), request.consumed(), request.minimumStock());
    var created = service.upsertAtLocation(user, locationId, command);
    return ResponseEntity.status(HttpStatus.CREATED).body(toView(created));
  }

  @Operation(operationId = "adjustInventoryLocationStockBalance",
      summary = "Apply a signed delta to available at one location (409 NEGATIVE_STOCK_REJECTED on underflow)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Stock adjusted",
          content = @Content(schema = @Schema(implementation = SparepartStockView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Stock balance or location not found"),
      @ApiResponse(responseCode = "409", description = "NEGATIVE_STOCK_REJECTED")
  })
  @PostMapping("/{materialCode}/adjust")
  public SparepartStockView adjust(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID locationId, @PathVariable String materialCode,
      @Valid @RequestBody AdjustLocationStockRequest request) {
    return toView(service.adjustAtLocation(user, locationId, materialCode, request.delta()));
  }

  private SparepartStockView toView(InventoryStockBalance balance) {
    var sparepart = spareparts.findById(balance.sparepartId()).orElse(null);
    var materialCode = sparepart != null ? sparepart.getMaterialCode() : null;
    var sparepartCode = sparepart != null ? sparepart.getCode() : null;
    var sparepartName = sparepart != null ? sparepart.getName() : null;
    var location = locations.findById(balance.locationId()).orElse(null);
    var locationCode = location != null ? location.getCode() : null;
    var plantId = location != null ? location.getPlantId() : null;
    return SparepartStockView.from(balance, materialCode, sparepartCode, sparepartName,
        locationCode, plantId);
  }
}
