package com.syncro.projection.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.projection.api.ProjectionDtos.MachineSparepartProjectionsView;
import com.syncro.projection.application.SparepartProjectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProjectionController {

  private final SparepartProjectionService projections;

  public ProjectionController(SparepartProjectionService projections) {
    this.projections = projections;
  }

  @Operation(operationId = "getMachineSparepartProjections",
      summary = "Get counter-rate estimate and per-installation depletion projections for a machine")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Projection view returned; insufficient data is explicit, never guessed",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = MachineSparepartProjectionsView.class))),
      @ApiResponse(responseCode = "400", description = "Invalid path value",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "401", description = "Authentication required",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "403", description = "Forbidden (plant scope)",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "404", description = "Machine not found",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  })
  @GetMapping("/api/v1/machines/{machineId}/sparepart-projections")
  public ResponseEntity<MachineSparepartProjectionsView> getMachineSparepartProjections(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID machineId) {
    return ResponseEntity.ok(projections.getProjections(user, machineId));
  }
}
