package com.syncro.maintenance.preventive.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.preventive.api.PreventiveDtos.CreatePreventiveProgramRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PreventiveProgramView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.UpdatePreventiveProgramRequest;
import com.syncro.maintenance.preventive.application.PreventiveProgramService;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.CreateProgramCommand;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.UpdateProgramCommand;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/preventive-programs")
public class PreventiveProgramController {

  private final PreventiveProgramService programs;

  public PreventiveProgramController(PreventiveProgramService programs) {
    this.programs = programs;
  }

  @Operation(operationId = "createPreventiveProgram", summary = "Create a preventive program and generate its schedule window")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Program created",
          content = @Content(schema = @Schema(implementation = PreventiveProgramView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine not found")
  })
  @PostMapping
  public ResponseEntity<PreventiveProgramView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreatePreventiveProgramRequest request) {
    var program = programs.create(user, new CreateProgramCommand(request.machineId(), request.category(),
        request.scheduleType(), request.dayOfMonth(), request.monthOfYear(), request.title(), request.description(),
        Boolean.TRUE.equals(request.autoWorkorder())));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/preventive-programs/" + program.id()))
        .body(toView(program));
  }

  @Operation(operationId = "listPreventivePrograms", summary = "List preventive programs")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Programs returned",
          content = @Content(schema = @Schema(implementation = PreventiveProgramView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public List<PreventiveProgramView> list(@AuthenticationPrincipal AuthenticatedUser user) {
    return programs.list(user).stream().map(PreventiveProgramController::toView).toList();
  }

  @Operation(operationId = "getPreventiveProgram", summary = "Get a preventive program")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Program returned",
          content = @Content(schema = @Schema(implementation = PreventiveProgramView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Program not found")
  })
  @GetMapping("/{id}")
  public PreventiveProgramView get(@PathVariable UUID id) {
    return toView(programs.get(id.toString()));
  }

  @Operation(operationId = "updatePreventiveProgram", summary = "Update a preventive program")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Program updated",
          content = @Content(schema = @Schema(implementation = PreventiveProgramView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Program not found")
  })
  @PutMapping("/{id}")
  public PreventiveProgramView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
      @Valid @RequestBody UpdatePreventiveProgramRequest request) {
    var program = programs.update(user, id.toString(), new UpdateProgramCommand(request.dayOfMonth(),
        request.monthOfYear(), request.title(), request.description(), request.active(),
        request.autoWorkorder()));
    return toView(program);
  }

  @Operation(operationId = "deletePreventiveProgram", summary = "Delete a preventive program (cascades schedules)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Program removed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Program not found")
  })
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    programs.delete(user, id.toString());
    return ResponseEntity.noContent().build();
  }

  @Operation(operationId = "generatePreventiveSchedules", summary = "Regenerate a program's schedule window idempotently")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Window generated",
          content = @Content(schema = @Schema(implementation = PreventiveProgramView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Program not found")
  })
  @PostMapping("/{id}/generate")
  public ResponseEntity<Void> generate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    programs.generate(user, id.toString());
    return ResponseEntity.ok().build();
  }

  private static PreventiveProgramView toView(com.syncro.maintenance.preventive.domain.PreventiveProgram p) {
    return new PreventiveProgramView(p.id(), p.machineId(), p.category(), p.scheduleType(), p.dayOfMonth(),
        p.monthOfYear(), p.title(), p.description(), p.active(), p.autoWorkorder(), p.createdBy(), p.createdAt(),
        p.updatedAt());
  }
}