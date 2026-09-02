package com.syncro.inventory.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.inventory.api.InventoryReservationDtos.ConsumeInventoryReservationRequest;
import com.syncro.inventory.api.InventoryReservationDtos.CreateInventoryReservationRequest;
import com.syncro.inventory.api.InventoryReservationDtos.InventoryReservationListView;
import com.syncro.inventory.api.InventoryReservationDtos.InventoryReservationView;
import com.syncro.inventory.application.InventoryReservationService;
import com.syncro.inventory.application.InventoryReservationService.CreateReservationCommand;
import com.syncro.inventory.application.InventoryReservationService.ReservationView;
import com.syncro.inventory.domain.InventoryReservationStatus;
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
 * Inventory-reservations REST surface (story 18-5, blueprint E4). Create reserves
 * stock atomically (available → reserved, 409 INSUFFICIENT_STOCK when the balance
 * cannot cover); consume draws the remaining quantity down (partial or full);
 * cancel returns the remaining quantity to available. List is plant-scoped with
 * optional sparepartId/locationId/status/referenceType/referenceId filters. No update,
 * no delete — CONSUMED/CANCELLED/EXPIRED are terminal. All permission, state-machine
 * and atomicity decisions live in the service; the controller only binds, delegates
 * and maps.
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory-reservations")
public class InventoryReservationController {

  private final InventoryReservationService service;

  public InventoryReservationController(InventoryReservationService service) {
    this.service = service;
  }

  @Operation(operationId = "listInventoryReservations",
      summary = "List reservations visible to the caller (optional sparepart/location/status/reference filters)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Reservations returned"),
      @ApiResponse(responseCode = "400", description = "INVALID_QUERY_VALUE"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public InventoryReservationListView list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID sparepartId,
      @RequestParam(required = false) UUID locationId,
      @RequestParam(required = false) InventoryReservationStatus status,
      @RequestParam(required = false) String referenceType,
      @RequestParam(required = false) String referenceId) {
    var items = service.list(user, sparepartId, locationId, status, referenceType, referenceId)
        .stream().map(InventoryReservationController::toView).toList();
    return new InventoryReservationListView(items);
  }

  @Operation(operationId = "getInventoryReservation",
      summary = "Get one reservation by id (expiry evaluated on access)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Reservation returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "INVENTORY_RESERVATION_NOT_FOUND")
  })
  @GetMapping("/{id}")
  public InventoryReservationView get(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return toView(service.get(user, id));
  }

  @Operation(operationId = "createInventoryReservation",
      summary = "Reserve stock (atomic available -> reserved move, audit CREATE)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Reservation created",
          content = @Content(schema = @Schema(implementation = InventoryReservationView.class))),
      @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "SPAREPART_NOT_FOUND / INVENTORY_LOCATION_NOT_FOUND"),
      @ApiResponse(responseCode = "409", description = "INSUFFICIENT_STOCK")
  })
  @PostMapping
  public ResponseEntity<InventoryReservationView> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateInventoryReservationRequest request) {
    var created = service.create(user, new CreateReservationCommand(request.sparepartId(),
        request.locationId(), request.quantity(), request.referenceType(),
        request.referenceId(), request.expiresAt()));
    return ResponseEntity.status(HttpStatus.CREATED).body(toView(created));
  }

  @Operation(operationId = "consumeInventoryReservation",
      summary = "Consume part of an ACTIVE reservation (remaining draws down)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Reservation consumed (fully or partially)"),
      @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "INVENTORY_RESERVATION_NOT_FOUND"),
      @ApiResponse(responseCode = "409", description = "INVALID_RESERVATION_TRANSITION / INSUFFICIENT_STOCK")
  })
  @PostMapping("/{id}/consume")
  public InventoryReservationView consume(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody ConsumeInventoryReservationRequest request) {
    return toView(service.consume(user, id, request.quantity()));
  }

  @Operation(operationId = "cancelInventoryReservation",
      summary = "Cancel an ACTIVE reservation (remaining returns to available)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Reservation cancelled"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "INVENTORY_RESERVATION_NOT_FOUND"),
      @ApiResponse(responseCode = "409", description = "INVALID_RESERVATION_TRANSITION / INSUFFICIENT_STOCK")
  })
  @PostMapping("/{id}/cancel")
  public InventoryReservationView cancel(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return toView(service.cancel(user, id));
  }

  private static InventoryReservationView toView(ReservationView view) {
    return new InventoryReservationView(view.id(), view.sparepartId(), view.locationId(),
        view.quantity(), view.remainingQuantity(), view.status(), view.referenceType(),
        view.referenceId(), view.requestedBy(), view.consumedBy(), view.cancelledBy(),
        view.expiresAt(), view.createdAt(), view.updatedAt());
  }
}
