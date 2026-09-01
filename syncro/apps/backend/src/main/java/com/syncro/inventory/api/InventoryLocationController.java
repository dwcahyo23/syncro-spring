package com.syncro.inventory.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.inventory.api.InventoryLocationDtos.CreateInventoryLocationRequest;
import com.syncro.inventory.api.InventoryLocationDtos.InventoryLocationListView;
import com.syncro.inventory.api.InventoryLocationDtos.InventoryLocationView;
import com.syncro.inventory.api.InventoryLocationDtos.UpdateInventoryLocationRequest;
import com.syncro.inventory.application.InventoryLocationService;
import com.syncro.inventory.application.InventoryLocationService.CreateLocationCommand;
import com.syncro.inventory.application.InventoryLocationService.UpdateLocationCommand;
import io.swagger.v3.oas.annotations.Operation;
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
 * Inventory-locations REST surface (story 18-2). List requires {@code plantId} with
 * an optional {@code activeOnly} filter; create returns 201; update is a partial PUT
 * — provided fields replace their current value, absent fields keep it, so
 * {@code {"isActive": false}} alone deactivates. NO delete endpoint —
 * RESTRICT FKs from balances/transfers/reservations make deactivation the lifecycle
 * end. Permission and uniqueness decisions live in the service; the controller only
 * binds, delegates and maps.
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory-locations")
public class InventoryLocationController {

  private final InventoryLocationService service;

  public InventoryLocationController(InventoryLocationService service) {
    this.service = service;
  }

  @Operation(operationId = "listInventoryLocations",
      summary = "List inventory locations for a plant (optional activeOnly filter)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Locations returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "PLANT_NOT_FOUND")
  })
  @GetMapping
  public InventoryLocationListView list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam UUID plantId,
      @RequestParam(defaultValue = "false") boolean activeOnly) {
    var items = service.list(user, plantId, activeOnly).stream()
        .map(InventoryLocationController::toView)
        .toList();
    return new InventoryLocationListView(items);
  }

  @Operation(operationId = "getInventoryLocation", summary = "Get one inventory location by id")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Location returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "INVENTORY_LOCATION_NOT_FOUND")
  })
  @GetMapping("/{id}")
  public InventoryLocationView get(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return toView(service.get(user, id));
  }

  @Operation(operationId = "createInventoryLocation",
      summary = "Create an inventory location (is_active=true, audit CREATE)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Location created"),
      @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "PLANT_NOT_FOUND"),
      @ApiResponse(responseCode = "409", description = "DUPLICATE_LOCATION")
  })
  @PostMapping
  public ResponseEntity<InventoryLocationView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateInventoryLocationRequest request) {
    var created = service.create(user, new CreateLocationCommand(request.plantId(),
        request.code(), request.name(), request.description()));
    return ResponseEntity.status(HttpStatus.CREATED).body(toView(created));
  }

  @Operation(operationId = "updateInventoryLocation",
      summary = "Update an inventory location (rename / description / activate-deactivate, audit UPDATE)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Location updated"),
      @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "INVENTORY_LOCATION_NOT_FOUND"),
      @ApiResponse(responseCode = "409", description = "DUPLICATE_LOCATION")
  })
  @PutMapping("/{id}")
  public InventoryLocationView update(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody UpdateInventoryLocationRequest request) {
    return toView(service.update(user, id,
        new UpdateLocationCommand(request.code(), request.name(), request.description(),
            request.isActive())));
  }

  private static InventoryLocationView toView(InventoryLocationService.LocationView location) {
    return new InventoryLocationView(location.id(), location.plantId(), location.code(),
        location.name(), location.description(), location.active(), location.createdAt(),
        location.updatedAt());
  }
}
