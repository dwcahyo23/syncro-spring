package com.syncro.masterdata.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.masterdata.api.PlantDtos.PlantListResponse;
import com.syncro.masterdata.api.PlantDtos.PlantRequest;
import com.syncro.masterdata.api.PlantDtos.PlantView;
import com.syncro.masterdata.application.PlantService;
import com.syncro.masterdata.application.PlantService.CreatePlantCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plants")
public class PlantController {
  private final PlantService plants;

  public PlantController(PlantService plants) {
    this.plants = plants;
  }

  @Operation(operationId = "listPlants", summary = "List plants")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Plants returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public PlantListResponse list(@AuthenticationPrincipal AuthenticatedUser user) {
    return new PlantListResponse(plants.list(user).stream().map(this::toDto).toList());
  }

  @Operation(operationId = "getPlant", summary = "Get plant")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Plant returned"),
      @ApiResponse(responseCode = "400", description = "Invalid plant id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Plant not found")
  })
  @GetMapping("/{plantId}")
  public PlantView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID plantId) {
    return toDto(plants.get(user, plantId));
  }

  @Operation(operationId = "createPlant", summary = "Create plant")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Plant created", content = @Content(schema = @Schema(implementation = PlantView.class))),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, or duplicate plant code"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "409", description = "Plant data integrity conflict")
  })
  @PostMapping
  public ResponseEntity<PlantView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody PlantRequest request) {
    var created = toDto(plants.create(user, command(request)));
    return ResponseEntity.created(URI.create("/api/v1/plants/" + created.id())).body(created);
  }

  @Operation(operationId = "updatePlant", summary = "Update plant")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Plant updated"),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, duplicate plant code, or invalid plant id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Plant not found")
  })
  @PutMapping("/{plantId}")
  public PlantView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID plantId,
      @Valid @RequestBody PlantRequest request) {
    return toDto(plants.update(user, plantId, command(request)));
  }

  @Operation(operationId = "deletePlant", summary = "Delete plant")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Plant deleted", content = @Content),
      @ApiResponse(responseCode = "400", description = "Invalid plant id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Plant not found")
  })
  @DeleteMapping("/{plantId}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID plantId) {
    plants.delete(user, plantId);
    return ResponseEntity.noContent().build();
  }

  private CreatePlantCommand command(PlantRequest request) {
    return new CreatePlantCommand(request.code(), request.name());
  }

  private PlantView toDto(PlantService.PlantView plant) {
    return new PlantView(plant.id(), plant.code(), plant.name(), plant.createdAt(), plant.updatedAt());
  }
}
