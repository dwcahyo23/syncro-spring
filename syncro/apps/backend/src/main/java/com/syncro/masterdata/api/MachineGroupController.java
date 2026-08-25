package com.syncro.masterdata.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.masterdata.api.MachineGroupDtos.MachineGroupListResponse;
import com.syncro.masterdata.api.MachineGroupDtos.MachineGroupRequest;
import com.syncro.masterdata.api.MachineGroupDtos.MachineGroupSectionRequest;
import com.syncro.masterdata.api.MachineGroupDtos.MachineGroupView;
import com.syncro.masterdata.application.MachineGroupService;
import com.syncro.masterdata.application.MachineGroupService.CreateMachineGroupCommand;
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
@RequestMapping("/api/v1/machine-groups")
public class MachineGroupController {
  private final MachineGroupService machineGroups;

  public MachineGroupController(MachineGroupService machineGroups) {
    this.machineGroups = machineGroups;
  }

  @Operation(operationId = "listMachineGroups", summary = "List machine groups")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Machine groups returned"),
      @ApiResponse(responseCode = "400", description = "Invalid plant id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Plant not found")
  })
  @GetMapping
  public MachineGroupListResponse list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam UUID plantId,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int size,
      @RequestParam(defaultValue = "name,asc") String sort) {
    var result = machineGroups.list(user, plantId, search, page, size, sort);
    return new MachineGroupListResponse(
        result.items().stream().map(this::toDto).toList(), result.totalElements(), result.page(), result.size(), result.sort());
  }

  @Operation(operationId = "getMachineGroup", summary = "Get machine group")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Machine group returned"),
      @ApiResponse(responseCode = "400", description = "Invalid machine group id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine group not found")
  })
  @GetMapping("/{machineGroupId}")
  public MachineGroupView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID machineGroupId) {
    return toDto(machineGroups.get(user, machineGroupId));
  }

  @Operation(operationId = "createMachineGroup", summary = "Create machine group")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Machine group created", content = @Content(schema = @Schema(implementation = MachineGroupView.class))),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, duplicate machine group name, or invalid plant id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Plant not found"),
      @ApiResponse(responseCode = "409", description = "Machine group data integrity conflict")
  })
  @PostMapping
  public ResponseEntity<MachineGroupView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody MachineGroupRequest request) {
    var created = toDto(machineGroups.create(user, command(request)));
    return ResponseEntity.created(URI.create("/api/v1/machine-groups/" + created.id())).body(created);
  }

  @Operation(operationId = "updateMachineGroup", summary = "Update machine group")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Machine group updated"),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, duplicate machine group name, or invalid machine group id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine group or plant not found"),
      @ApiResponse(responseCode = "409", description = "Machine group data integrity conflict")
  })
  @PutMapping("/{machineGroupId}")
  public MachineGroupView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID machineGroupId,
      @Valid @RequestBody MachineGroupRequest request) {
    return toDto(machineGroups.update(user, machineGroupId, command(request)));
  }

  @Operation(operationId = "deleteMachineGroup", summary = "Delete machine group")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Machine group deleted", content = @Content),
      @ApiResponse(responseCode = "400", description = "Invalid machine group id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine group not found"),
      @ApiResponse(responseCode = "409", description = "Machine group data integrity conflict")
  })
  @DeleteMapping("/{machineGroupId}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID machineGroupId) {
    machineGroups.delete(user, machineGroupId);
    return ResponseEntity.noContent().build();
  }

  @Operation(operationId = "assignMachineGroupSection",
      summary = "Assign a machine group to a section (set-once; reassignment is rejected)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Machine group assigned to section", content = @Content),
      @ApiResponse(responseCode = "400", description = "Section plant mismatch or section reassignment rejected"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine group or section not found")
  })
  @PutMapping("/{machineGroupId}/section")
  public ResponseEntity<Void> assignSection(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID machineGroupId, @Valid @RequestBody MachineGroupSectionRequest request) {
    machineGroups.assignSection(user, machineGroupId, request.sectionId());
    return ResponseEntity.noContent().build();
  }

  @Operation(operationId = "clearMachineGroupSection",
      summary = "Clear a machine group's section assignment")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Machine group section cleared (or absent, no-op)", content = @Content),
      @ApiResponse(responseCode = "400", description = "Invalid machine group id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine group not found")
  })
  @DeleteMapping("/{machineGroupId}/section")
  public ResponseEntity<Void> clearSection(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID machineGroupId) {
    machineGroups.clearSection(user, machineGroupId);
    return ResponseEntity.noContent().build();
  }

  private CreateMachineGroupCommand command(MachineGroupRequest request) {
    return new CreateMachineGroupCommand(request.plantId(), request.name());
  }

  private MachineGroupView toDto(MachineGroupService.MachineGroupView machineGroup) {
    return new MachineGroupView(
        machineGroup.id(),
        machineGroup.plantId(),
        machineGroup.plantCode(),
        machineGroup.plantName(),
        machineGroup.name(),
        machineGroup.sectionId(),
        machineGroup.sectionCode(),
        machineGroup.sectionName(),
        machineGroup.createdAt(),
        machineGroup.updatedAt());
  }
}
