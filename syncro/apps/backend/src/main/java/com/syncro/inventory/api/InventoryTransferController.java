package com.syncro.inventory.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.inventory.api.InventoryTransferDtos.CreateInventoryTransferRequest;
import com.syncro.inventory.api.InventoryTransferDtos.InventoryTransferListView;
import com.syncro.inventory.api.InventoryTransferDtos.InventoryTransferView;
import com.syncro.inventory.api.InventoryTransferDtos.RejectInventoryTransferRequest;
import com.syncro.inventory.application.InventoryTransferService;
import com.syncro.inventory.application.InventoryTransferService.CreateTransferCommand;
import com.syncro.inventory.application.InventoryTransferService.TransferView;
import com.syncro.inventory.domain.InventoryTransferStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inventory-transfers REST surface (story 18-4, blueprint E3). Create requests a
 * PENDING_APPROVAL transfer; approve moves stock atomically (source debit +
 * destination credit in one transaction); reject stores a mandatory reason. List is
 * plant-scoped with optional sparepartId/status filters. No update, no delete —
 * APPROVED/REJECTED are terminal. All permission, SoD, state-machine and atomicity
 * decisions live in the service; the controller only binds, delegates and maps.
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory-transfers")
public class InventoryTransferController {

  private final InventoryTransferService service;

  public InventoryTransferController(InventoryTransferService service) {
    this.service = service;
  }

  @Operation(operationId = "listInventoryTransfers",
      summary = "List transfers visible to the caller (optional sparepartId/status filters)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Transfers returned"),
      @ApiResponse(responseCode = "400", description = "INVALID_QUERY_VALUE"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public InventoryTransferListView list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID sparepartId,
      @RequestParam(required = false) InventoryTransferStatus status) {
    var items = service.list(user, sparepartId, status).stream()
        .map(InventoryTransferController::toView)
        .toList();
    return new InventoryTransferListView(items);
  }

  @Operation(operationId = "getInventoryTransfer", summary = "Get one transfer by id")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Transfer returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "INVENTORY_TRANSFER_NOT_FOUND")
  })
  @GetMapping("/{id}")
  public InventoryTransferView get(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return toView(service.get(user, id));
  }

  @Operation(operationId = "createInventoryTransfer",
      summary = "Request a stock transfer (PENDING_APPROVAL, audit CREATE)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Transfer requested",
          content = @Content(schema = @Schema(implementation = InventoryTransferView.class))),
      @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "SPAREPART_NOT_FOUND / INVENTORY_LOCATION_NOT_FOUND")
  })
  @PostMapping
  public ResponseEntity<InventoryTransferView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateInventoryTransferRequest request) {
    var created = service.create(user, new CreateTransferCommand(request.sparepartId(),
        request.sourceLocationId(), request.destinationLocationId(), request.quantity()));
    return ResponseEntity.status(HttpStatus.CREATED).body(toView(created));
  }

  @Operation(operationId = "approveInventoryTransfer",
      summary = "Approve a pending transfer (atomic source debit + destination credit)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Transfer approved, stock moved"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "FORBIDDEN / TRANSFER_SELF_REVIEW_FORBIDDEN"),
      @ApiResponse(responseCode = "404", description = "INVENTORY_TRANSFER_NOT_FOUND"),
      @ApiResponse(responseCode = "409", description = "INVALID_TRANSFER_TRANSITION / INSUFFICIENT_STOCK")
  })
  @PostMapping("/{id}/approve")
  public InventoryTransferView approve(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return toView(service.approve(user, id));
  }

  @Operation(operationId = "rejectInventoryTransfer",
      summary = "Reject a pending transfer with a mandatory reason (no stock movement)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Transfer rejected"),
      @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "FORBIDDEN / TRANSFER_SELF_REVIEW_FORBIDDEN"),
      @ApiResponse(responseCode = "404", description = "INVENTORY_TRANSFER_NOT_FOUND"),
      @ApiResponse(responseCode = "409", description = "INVALID_TRANSFER_TRANSITION")
  })
  @PostMapping("/{id}/reject")
  public InventoryTransferView reject(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody RejectInventoryTransferRequest request) {
    return toView(service.reject(user, id, request.rejectionReason()));
  }

  private static InventoryTransferView toView(TransferView view) {
    return new InventoryTransferView(view.id(), view.sparepartId(), view.sourceLocationId(),
        view.destinationLocationId(), view.quantity(), view.status(), view.requestedBy(),
        view.reviewedBy(), view.rejectionReason(), view.reviewedAt(), view.createdAt(),
        view.updatedAt());
  }
}
