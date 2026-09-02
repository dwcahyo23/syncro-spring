package com.syncro.maintenance.preventive.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.preventive.api.PreventiveDtos.CreateFrequencyRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.FrequencyView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.UpdateFrequencyRequest;
import com.syncro.maintenance.preventive.application.PmFrequencyService;
import com.syncro.maintenance.preventive.application.PmFrequencyService.CreateFrequencyCommand;
import com.syncro.maintenance.preventive.application.PmFrequencyService.UpdateFrequencyCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

/** PM frequency master-data API (story 19-1, blueprint F1). */
@Validated
@RestController
@RequestMapping("/api/v1/pm-frequencies")
public class PmFrequencyController {

  private final PmFrequencyService frequencies;

  public PmFrequencyController(PmFrequencyService frequencies) {
    this.frequencies = frequencies;
  }

  @Operation(operationId = "listPmFrequencies", summary = "List PM frequencies (sort_order asc)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Frequencies returned",
          content = @Content(schema = @Schema(implementation = FrequencyView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public List<FrequencyView> list() {
    return frequencies.list().stream().map(PmFrequencyController::toView).toList();
  }

  @Operation(operationId = "getPmFrequency", summary = "Get a PM frequency")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Frequency returned",
          content = @Content(schema = @Schema(implementation = FrequencyView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Frequency not found")
  })
  @GetMapping("/{id}")
  public FrequencyView get(@PathVariable UUID id) {
    return toView(frequencies.get(id));
  }

  @Operation(operationId = "createPmFrequency", summary = "Create a PM frequency")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Frequency created",
          content = @Content(schema = @Schema(implementation = FrequencyView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "409", description = "Duplicate frequency code")
  })
  @PostMapping
  public ResponseEntity<FrequencyView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateFrequencyRequest request) {
    var frequency = frequencies.create(user, new CreateFrequencyCommand(request.code(),
        request.name(), request.description(), request.sortOrder(), request.isActive()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/pm-frequencies/" + frequency.id()))
        .body(toView(frequency));
  }

  @Operation(operationId = "updatePmFrequency", summary = "Update a PM frequency")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Frequency updated",
          content = @Content(schema = @Schema(implementation = FrequencyView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Frequency not found")
  })
  @PutMapping("/{id}")
  public FrequencyView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
      @Valid @RequestBody UpdateFrequencyRequest request) {
    return toView(frequencies.update(user, id, new UpdateFrequencyCommand(request.name(),
        request.description(), request.sortOrder(), request.isActive())));
  }

  private static FrequencyView toView(com.syncro.maintenance.preventive.application.PmFrequencyService.FrequencyView f) {
    return new FrequencyView(f.id(), f.code(), f.name(), f.description(), f.sortOrder(), f.active(),
        f.createdAt(), f.updatedAt());
  }
}
