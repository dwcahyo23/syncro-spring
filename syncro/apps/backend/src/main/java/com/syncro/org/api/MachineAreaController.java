package com.syncro.org.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.org.api.MachineAreaDtos.CreateMachineAreaRequest;
import com.syncro.org.api.MachineAreaDtos.MachineAreaListResponse;
import com.syncro.org.api.MachineAreaDtos.MachineAreaView;
import com.syncro.org.api.MachineAreaDtos.UpdateMachineAreaRequest;
import com.syncro.org.application.MachineAreaService;
import com.syncro.org.application.MachineAreaService.CreateMachineAreaCommand;
import com.syncro.org.application.MachineAreaService.UpdateMachineAreaCommand;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/api/v1/machine-areas")
public class MachineAreaController {

  private final MachineAreaService areas;

  public MachineAreaController(MachineAreaService areas) {
    this.areas = areas;
  }

  @Operation(operationId = "listMachineAreas", summary = "List machine areas for a plant")
  @GetMapping
  public MachineAreaListResponse list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam UUID plantId,
      @RequestParam(defaultValue = "false") boolean includeInactive) {
    var result = areas.list(user, plantId, includeInactive);
    return new MachineAreaListResponse(result.items().stream().map(this::toDto).toList());
  }

  @Operation(operationId = "getMachineArea", summary = "Get a machine area")
  @GetMapping("/{areaId}")
  public MachineAreaView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID areaId) {
    return toDto(areas.get(user, areaId));
  }

  @Operation(operationId = "createMachineArea", summary = "Create a machine area")
  @PostMapping
  public ResponseEntity<MachineAreaView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateMachineAreaRequest request) {
    var created = toDto(areas.create(user,
        new CreateMachineAreaCommand(request.plantId(), request.code(), request.name(), request.description())));
    return ResponseEntity.created(URI.create("/api/v1/machine-areas/" + created.id())).body(created);
  }

  @Operation(operationId = "updateMachineArea", summary = "Update a machine area")
  @PutMapping("/{areaId}")
  public MachineAreaView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID areaId,
      @Valid @RequestBody UpdateMachineAreaRequest request) {
    return toDto(areas.update(user, areaId,
        new UpdateMachineAreaCommand(request.code(), request.name(), request.description(), request.active())));
  }

  /** Deactivate-style delete: rejected while machines reference the area, otherwise soft-inactive. */
  @Operation(operationId = "deleteMachineArea", summary = "Deactivate a machine area")
  @DeleteMapping("/{areaId}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID areaId) {
    areas.delete(user, areaId);
    return ResponseEntity.noContent().build();
  }

  private MachineAreaView toDto(MachineAreaService.MachineAreaView area) {
    return new MachineAreaView(
        area.id(),
        area.plantId(),
        area.plantCode(),
        area.plantName(),
        area.code(),
        area.name(),
        area.description(),
        area.active(),
        area.createdAt(),
        area.updatedAt());
  }
}
