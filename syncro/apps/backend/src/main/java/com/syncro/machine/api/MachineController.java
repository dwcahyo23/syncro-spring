package com.syncro.machine.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.machine.api.MachineDtos.MachineListResponse;
import com.syncro.machine.api.MachineDtos.MachineRequest;
import com.syncro.machine.api.MachineDtos.MachineView;
import com.syncro.machine.application.MachineService;
import com.syncro.machine.application.MachineService.MachineCommand;
import com.syncro.machine.domain.MachineStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/machines")
public class MachineController {
  private final MachineService machines;

  public MachineController(MachineService machines) {
    this.machines = machines;
  }

  @Operation(operationId = "listMachines", summary = "List machines")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Machines returned"),
      @ApiResponse(responseCode = "400", description = "Invalid filter"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Plant or machine group not found")
  })
  @GetMapping
  public MachineListResponse list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID plantId,
      @RequestParam(required = false) UUID machineGroupId,
      @RequestParam(required = false) MachineStatus status) {
    return new MachineListResponse(machines.list(user, plantId, machineGroupId, status).stream().map(this::toDto).toList());
  }

  @Operation(operationId = "getMachine", summary = "Get machine")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Machine returned"),
      @ApiResponse(responseCode = "400", description = "Invalid machine id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine not found")
  })
  @GetMapping("/{machineId}")
  public MachineView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID machineId) {
    return toDto(machines.get(user, machineId));
  }

  @Operation(operationId = "createMachine", summary = "Create machine")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Machine created", content = @Content(schema = @Schema(implementation = MachineView.class))),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, duplicate code, invalid status, or invalid relation"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Plant or machine group not found"),
      @ApiResponse(responseCode = "409", description = "Machine data integrity conflict")
  })
  @PostMapping
  public ResponseEntity<MachineView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody MachineRequest request) {
    var created = toDto(machines.create(user, command(request)));
    return ResponseEntity.created(URI.create("/api/v1/machines/" + created.id())).body(created);
  }

  @Operation(operationId = "updateMachine", summary = "Update machine")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Machine updated"),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, duplicate code, invalid status, or invalid relation"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine, plant, or machine group not found"),
      @ApiResponse(responseCode = "409", description = "Machine data integrity conflict")
  })
  @PutMapping("/{machineId}")
  public MachineView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID machineId,
      @Valid @RequestBody MachineRequest request) {
    return toDto(machines.update(user, machineId, command(request)));
  }

  @Operation(operationId = "deleteMachine", summary = "Delete machine")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Machine deleted", content = @Content),
      @ApiResponse(responseCode = "400", description = "Invalid machine id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine not found"),
      @ApiResponse(responseCode = "409", description = "Machine data integrity conflict")
  })
  @DeleteMapping("/{machineId}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID machineId) {
    machines.delete(user, machineId);
    return ResponseEntity.noContent().build();
  }

  private MachineCommand command(MachineRequest request) {
    return new MachineCommand(request.plantId(), request.machineGroupId(), request.code(), request.name(), request.status(),
        request.brand(), request.installedAt(), request.notes());
  }

  private MachineView toDto(MachineService.MachineView machine) {
    return new MachineView(machine.id(), machine.plantId(), machine.plantCode(), machine.plantName(), machine.machineGroupId(),
        machine.machineGroupName(), machine.code(), machine.name(), machine.status(), machine.brand(), machine.installedAt(),
        machine.notes(), machine.createdAt(), machine.updatedAt());
  }
}
