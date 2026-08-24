package com.syncro.shiftconfig.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.shiftconfig.api.ShiftConfigDtos.MachineGroupShiftConfigView;
import com.syncro.shiftconfig.api.ShiftConfigDtos.MachineShiftConfigView;
import com.syncro.shiftconfig.api.ShiftConfigDtos.SetShiftConfigRequest;
import com.syncro.shiftconfig.api.ShiftConfigDtos.ShiftWindowRequest;
import com.syncro.shiftconfig.api.ShiftConfigDtos.ShiftWindowView;
import com.syncro.shiftconfig.application.ShiftConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShiftConfigController {
  private static final DateTimeFormatter WIRE_TIME = DateTimeFormatter.ofPattern("HH:mm");

  private final ShiftConfigService shiftConfigs;

  public ShiftConfigController(ShiftConfigService shiftConfigs) {
    this.shiftConfigs = shiftConfigs;
  }

  @Operation(operationId = "updateMachineGroupShiftConfig",
      summary = "Replace a machine group's shift schedule")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Shift schedule stored; empty array clears it",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = MachineGroupShiftConfigView.class))),
      @ApiResponse(responseCode = "400", description = "Validation error",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "401", description = "Authentication required",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "403", description = "Forbidden (role, job scope, or plant)",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "404", description = "Machine group not found",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  })
  @PutMapping("/api/v1/machine-groups/{machineGroupId}/shift-config")
  public ResponseEntity<MachineGroupShiftConfigView> updateGroupConfig(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID machineGroupId,
      @RequestBody SetShiftConfigRequest request) {
    var view = shiftConfigs.setGroupConfig(user, machineGroupId, toCommands(request.shifts()));
    return ResponseEntity.ok(toGroupDto(view));
  }

  @Operation(operationId = "getMachineGroupShiftConfig",
      summary = "Get a machine group's shift schedule")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Shift schedule returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = MachineGroupShiftConfigView.class))),
      @ApiResponse(responseCode = "400", description = "Invalid path value",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "401", description = "Authentication required",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "403", description = "Forbidden (plant scope)",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "404", description = "Machine group not found",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  })
  @GetMapping("/api/v1/machine-groups/{machineGroupId}/shift-config")
  public ResponseEntity<MachineGroupShiftConfigView> getGroupConfig(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID machineGroupId) {
    return ResponseEntity.ok(toGroupDto(shiftConfigs.getGroupConfig(user, machineGroupId)));
  }

  @Operation(operationId = "updateMachineShiftConfig",
      summary = "Replace a machine's shift override")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Override stored; empty array clears it",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = MachineShiftConfigView.class))),
      @ApiResponse(responseCode = "400", description = "Validation error",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "401", description = "Authentication required",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "403", description = "Forbidden (role, job scope, or plant)",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "404", description = "Machine not found",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  })
  @PutMapping("/api/v1/machines/{machineId}/shift-config")
  public ResponseEntity<MachineShiftConfigView> updateMachineConfig(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID machineId,
      @RequestBody SetShiftConfigRequest request) {
    var view = shiftConfigs.setMachineConfig(user, machineId, toCommands(request.shifts()));
    return ResponseEntity.ok(toMachineDto(view));
  }

  @Operation(operationId = "getMachineShiftConfig",
      summary = "Get a machine's effective shift schedule with resolved source")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Effective shift schedule returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = MachineShiftConfigView.class))),
      @ApiResponse(responseCode = "400", description = "Invalid path value",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "401", description = "Authentication required",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "403", description = "Forbidden (plant scope)",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "404", description = "Machine not found",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  })
  @GetMapping("/api/v1/machines/{machineId}/shift-config")
  public ResponseEntity<MachineShiftConfigView> getMachineConfig(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID machineId) {
    return ResponseEntity.ok(toMachineDto(shiftConfigs.getMachineConfig(user, machineId)));
  }

  @Operation(operationId = "deleteMachineShiftConfig",
      summary = "Clear a machine's shift override (falls back to its group)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Override removed (or absent, no-op)",
          content = @Content),
      @ApiResponse(responseCode = "400", description = "Invalid path value",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "401", description = "Authentication required",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "403", description = "Forbidden (role, job scope, or plant)",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "404", description = "Machine not found",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  })
  @DeleteMapping("/api/v1/machines/{machineId}/shift-config")
  public ResponseEntity<Void> deleteMachineConfig(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID machineId) {
    shiftConfigs.clearMachineConfig(user, machineId);
    return ResponseEntity.noContent().build();
  }

  private List<ShiftConfigService.ShiftWindowCommand> toCommands(List<ShiftWindowRequest> requests) {
    // A missing/null "shifts" key must not masquerade as an intentional clear (empty array is
    // the only clear signal), otherwise a truncated payload would audited-delete the schedule.
    if (requests == null) {
      throw new ShiftConfigService.ValidationException(Map.of("shifts", "Shifts payload is required."));
    }
    return requests.stream()
        .map(request -> request == null
            ? new ShiftConfigService.ShiftWindowCommand(null, null)
            : new ShiftConfigService.ShiftWindowCommand(request.startTime(), request.endTime()))
        .toList();
  }

  private MachineGroupShiftConfigView toGroupDto(ShiftConfigService.MachineGroupShiftConfigView view) {
    return new MachineGroupShiftConfigView(
        view.shifts().stream().map(this::toShiftDto).toList());
  }

  private MachineShiftConfigView toMachineDto(ShiftConfigService.MachineShiftConfigView view) {
    return new MachineShiftConfigView(view.source(), view.inheritedFromGroup(),
        view.shifts().stream().map(this::toShiftDto).toList());
  }

  private ShiftWindowView toShiftDto(ShiftConfigService.ShiftWindowView window) {
    return new ShiftWindowView(window.shiftNumber(), window.startTime().format(WIRE_TIME),
        window.endTime().format(WIRE_TIME));
  }
}