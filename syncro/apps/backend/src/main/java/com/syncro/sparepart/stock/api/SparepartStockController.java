package com.syncro.sparepart.stock.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.sparepart.stock.api.SparepartStockDtos.AdjustSparepartStockRequest;
import com.syncro.sparepart.stock.api.SparepartStockDtos.CreateSparepartStockRequest;
import com.syncro.sparepart.stock.api.SparepartStockDtos.SparepartStockListView;
import com.syncro.sparepart.stock.api.SparepartStockDtos.SparepartStockView;
import com.syncro.sparepart.stock.api.SparepartStockDtos.UpdateSparepartStockRequest;
import com.syncro.sparepart.stock.application.SparepartStockService;
import com.syncro.sparepart.stock.application.SparepartStockService.AdjustStockCommand;
import com.syncro.sparepart.stock.application.SparepartStockService.UpdateStockCommand;
import com.syncro.sparepart.stock.application.SparepartStockService.UpsertStockCommand;
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

@Validated
@RestController
@RequestMapping("/api/v1/sparepart-stock")
public class SparepartStockController {

  private final SparepartStockService service;

  public SparepartStockController(SparepartStockService service) {
    this.service = service;
  }

  @Operation(operationId = "listSparepartStock", summary = "List stock rows for a plant (FR-146)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Stock rows returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  @GetMapping
  public SparepartStockListView list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam UUID plantId) {
    var items = service.list(user, plantId).stream()
        .map(SparepartStockView::from)
        .toList();
    return new SparepartStockListView(items);
  }

  @Operation(operationId = "listSparepartStockReorderWarnings",
      summary = "Reorder-warning rows for a plant (stock_on_hand <= order_point)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Reorder warnings returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  @GetMapping("/reorder-warnings")
  public SparepartStockListView reorderWarnings(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam UUID plantId) {
    var items = service.reorderWarnings(user, plantId).stream()
        .map(SparepartStockView::from)
        .toList();
    return new SparepartStockListView(items);
  }

  @Operation(operationId = "createSparepartStock",
      summary = "Create (or upsert) a stock row for a material code and plant (FR-146)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Stock row created",
          content = @Content(schema = @Schema(implementation = SparepartStockView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Sparepart not found")
  })
  @PostMapping
  public ResponseEntity<SparepartStockView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateSparepartStockRequest request) {
    var command = new UpsertStockCommand(request.materialCode(), request.plantId(), request.stockOnHand(),
        request.orderPoint(), request.orderQty());
    var created = service.upsert(user, command);
    return ResponseEntity.status(HttpStatus.CREATED).body(SparepartStockView.from(created));
  }

  @Operation(operationId = "updateSparepartStock",
      summary = "Partially overwrite a stock row (optimistic lock; 409 VERSION_CONFLICT on mismatch)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Stock row updated",
          content = @Content(schema = @Schema(implementation = SparepartStockView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Stock row not found"),
      @ApiResponse(responseCode = "409", description = "VERSION_CONFLICT")
  })
  @PutMapping("/{materialCode}")
  public SparepartStockView update(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String materialCode, @Valid @RequestBody UpdateSparepartStockRequest request) {
    var command = new UpdateStockCommand(request.plantId(), request.version(), request.stockOnHand(),
        request.orderPoint(), request.orderQty());
    return SparepartStockView.from(service.update(user, materialCode, command));
  }

  @Operation(operationId = "adjustSparepartStock",
      summary = "Apply a signed delta to stock_on_hand atomically (409 NEGATIVE_STOCK_REJECTED on underflow)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Stock adjusted",
          content = @Content(schema = @Schema(implementation = SparepartStockView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Stock row not found"),
      @ApiResponse(responseCode = "409", description = "NEGATIVE_STOCK_REJECTED")
  })
  @PostMapping("/{materialCode}/adjust")
  public SparepartStockView adjust(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String materialCode, @Valid @RequestBody AdjustSparepartStockRequest request) {
    var command = new AdjustStockCommand(request.plantId(), request.delta());
    return SparepartStockView.from(service.adjust(user, materialCode, command));
  }
}
