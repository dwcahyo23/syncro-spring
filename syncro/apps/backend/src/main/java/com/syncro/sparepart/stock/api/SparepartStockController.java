package com.syncro.sparepart.stock.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.inventory.application.InventoryStockService;
import com.syncro.inventory.application.InventoryStockService.AdjustBalanceCommand;
import com.syncro.inventory.application.InventoryStockService.UpdateBalanceCommand;
import com.syncro.inventory.application.InventoryStockService.UpsertBalanceCommand;
import com.syncro.inventory.domain.InventoryStockBalance;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.stock.api.SparepartStockDtos.AdjustSparepartStockRequest;
import com.syncro.sparepart.stock.api.SparepartStockDtos.CreateSparepartStockRequest;
import com.syncro.sparepart.stock.api.SparepartStockDtos.SparepartStockListView;
import com.syncro.sparepart.stock.api.SparepartStockDtos.SparepartStockView;
import com.syncro.sparepart.stock.api.SparepartStockDtos.UpdateSparepartStockRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sparepart-stock read/write API (FR-146). Story 15-1: the storage behind these
 * endpoints is {@code inventory_stock_balances} (blueprint E2); the URL contract and
 * the material-code + plant addressing are unchanged so the frontend keeps working.
 * Values carry the new available/reserved/consumed/minimumStock semantics.
 */
@Validated
@RestController
@RequestMapping("/api/v1/sparepart-stock")
public class SparepartStockController {

  private final InventoryStockService service;
  private final SparepartRepository spareparts;
  private final InventoryLocationRepository locations;

  public SparepartStockController(InventoryStockService service, SparepartRepository spareparts,
      InventoryLocationRepository locations) {
    this.service = service;
    this.spareparts = spareparts;
    this.locations = locations;
  }

  @Operation(operationId = "listSparepartStock", summary = "List stock balances for a plant (FR-146)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Stock balances returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  @GetMapping
  public SparepartStockListView list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam UUID plantId) {
    var items = service.list(user, plantId).stream()
        .map(this::toView)
        .toList();
    return new SparepartStockListView(items);
  }

  @Operation(operationId = "listSparepartStockReorderWarnings",
      summary = "Reorder-warning rows for a plant (available <= minimum_stock)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Reorder warnings returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  @GetMapping("/reorder-warnings")
  public SparepartStockListView reorderWarnings(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam UUID plantId) {
    var items = service.reorderWarnings(user, plantId).stream()
        .map(this::toView)
        .toList();
    return new SparepartStockListView(items);
  }

  @Operation(operationId = "createSparepartStock",
      summary = "Create (or upsert) a stock balance for a material code and plant (FR-146)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Stock balance created",
          content = @Content(schema = @Schema(implementation = SparepartStockView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Sparepart not found")
  })
  @PostMapping
  public ResponseEntity<SparepartStockView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateSparepartStockRequest request) {
    var command = new UpsertBalanceCommand(request.materialCode(), request.plantId(),
        request.available(), request.reserved(), request.consumed(), request.minimumStock());
    var created = service.upsert(user, command);
    return ResponseEntity.status(HttpStatus.CREATED).body(toView(created));
  }

  @Operation(operationId = "updateSparepartStock",
      summary = "Partially overwrite a stock balance (optimistic lock; 409 VERSION_CONFLICT on mismatch)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Stock balance updated",
          content = @Content(schema = @Schema(implementation = SparepartStockView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Stock balance not found"),
      @ApiResponse(responseCode = "409", description = "VERSION_CONFLICT")
  })
  @PutMapping("/{materialCode}")
  public SparepartStockView update(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String materialCode, @Valid @RequestBody UpdateSparepartStockRequest request) {
    var command = new UpdateBalanceCommand(request.plantId(), request.version(),
        request.available(), request.reserved(), request.consumed(), request.minimumStock());
    return toView(service.update(user, materialCode, command));
  }

  @Operation(operationId = "adjustSparepartStock",
      summary = "Apply a signed delta to available atomically (409 NEGATIVE_STOCK_REJECTED on underflow)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Stock adjusted",
          content = @Content(schema = @Schema(implementation = SparepartStockView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Stock balance not found"),
      @ApiResponse(responseCode = "409", description = "NEGATIVE_STOCK_REJECTED")
  })
  @PostMapping("/{materialCode}/adjust")
  public SparepartStockView adjust(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String materialCode, @Valid @RequestBody AdjustSparepartStockRequest request) {
    var command = new AdjustBalanceCommand(request.plantId(), request.delta());
    return toView(service.adjust(user, materialCode, command));
  }

  // -------------------------------------------------------------------------
  // View assembly (resolves the material code + plant for the read model)
  // -------------------------------------------------------------------------

  private SparepartStockView toView(InventoryStockBalance balance) {
    var sparepart = spareparts.findById(balance.sparepartId()).orElse(null);
    var materialCode = sparepart != null ? sparepart.getMaterialCode() : null;
    var plantId = locations.findById(balance.locationId())
        .map(location -> location.getPlantId())
        .orElse(null);
    return SparepartStockView.from(balance, materialCode, plantId);
  }
}
