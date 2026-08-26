package com.syncro.maintenance.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.api.WorkOrderDtos.CreateRatingDimensionRequest;
import com.syncro.maintenance.api.WorkOrderDtos.UpdateRatingDimensionRequest;
import com.syncro.maintenance.application.WorkOrderRatingService;
import com.syncro.maintenance.application.WorkOrderRatingService.CreateDimensionCommand;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingDimensionView;
import com.syncro.maintenance.application.WorkOrderRatingService.UpdateDimensionCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rating dimension configuration (FR-124, AD-14, story 10-8). Dimensions are shared
 * configuration data — reads are any-authenticated; mutations are SUPER_ADMIN-only
 * (service-gated, mirroring the OPA default-deny on these paths).
 */
@Validated
@RestController
@RequestMapping("/api/v1/rating-dimensions")
public class RatingDimensionController {

  private final WorkOrderRatingService ratings;

  public RatingDimensionController(WorkOrderRatingService ratings) {
    this.ratings = ratings;
  }

  @Operation(operationId = "listRatingDimensions", summary = "List rating dimensions ordered by sortOrder")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Dimensions returned",
          content = @Content(schema = @Schema(implementation = RatingDimensionView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public List<RatingDimensionView> list() {
    return ratings.listDimensions();
  }

  @Operation(operationId = "createRatingDimension", summary = "Create a rating dimension (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Dimension created",
          content = @Content(schema = @Schema(implementation = RatingDimensionView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only"),
      @ApiResponse(responseCode = "409", description = "Dimension code already exists")
  })
  @PostMapping
  public ResponseEntity<RatingDimensionView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateRatingDimensionRequest request) {
    var dimension = ratings.createDimension(user, new CreateDimensionCommand(request.code(), request.label(),
        request.sortOrder() != null ? request.sortOrder() : 0));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/rating-dimensions/" + dimension.code()))
        .body(dimension);
  }

  @Operation(operationId = "updateRatingDimension", summary = "Update a rating dimension's label/sortOrder (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Dimension updated",
          content = @Content(schema = @Schema(implementation = RatingDimensionView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only"),
      @ApiResponse(responseCode = "404", description = "Dimension not found")
  })
  @PutMapping("/{code}")
  public RatingDimensionView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code,
      @Valid @RequestBody UpdateRatingDimensionRequest request) {
    return ratings.updateDimension(user, code, new UpdateDimensionCommand(request.label(), request.sortOrder()));
  }

  @Operation(operationId = "deleteRatingDimension", summary = "Delete a rating dimension (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Dimension removed"),
      @ApiResponse(responseCode = "400", description = "Dimension in use by existing scores"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only"),
      @ApiResponse(responseCode = "404", description = "Dimension not found")
  })
  @DeleteMapping("/{code}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code) {
    ratings.deleteDimension(user, code);
    return ResponseEntity.noContent().build();
  }
}