package com.syncro.machine.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.machine.api.MachineResponsibilityDtos.CreateMachineResponsibilityRequest;
import com.syncro.machine.api.MachineResponsibilityDtos.MachineResponsibilityResponse;
import com.syncro.machine.application.MachineResponsibilityService;
import com.syncro.machine.api.MachineResponsibilityDtos.UpdateMachineResponsibilityRequest;
import com.syncro.machine.api.MachineResponsibilityDtos.PageResponse;
import org.springframework.data.domain.Pageable;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/machine-responsibilities")
public class MachineResponsibilityController {

  private final MachineResponsibilityService service;

  public MachineResponsibilityController(MachineResponsibilityService service) {
    this.service = service;
  }

  @Operation(operationId = "assignMachineResponsibility", summary = "Create machine responsibility")
  @PostMapping
  public ResponseEntity<MachineResponsibilityResponse> assign(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateMachineResponsibilityRequest request) {
    var response = service.assign(user, request);
    return ResponseEntity.created(URI.create("/api/v1/machine-responsibilities/" + response.id())).body(response);
  }

  @Operation(operationId = "unassignMachineResponsibility", summary = "Delete machine responsibility")
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> unassign(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    service.unassign(user, id);
    return ResponseEntity.noContent().build();
  }

  @Operation(operationId = "updateMachineResponsibility", summary = "Update machine responsibility")
  @org.springframework.web.bind.annotation.PutMapping("/{id}")
  public ResponseEntity<MachineResponsibilityResponse> update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id,
      @Valid @RequestBody UpdateMachineResponsibilityRequest request) {
    return ResponseEntity.ok(service.update(user, id, request));
  }

  @Operation(operationId = "listMachineResponsibilities", summary = "List machine responsibilities")
  @GetMapping
  public PageResponse<MachineResponsibilityResponse> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID machineId,
      Pageable pageable) {
    if (machineId != null) {
      return PageResponse.of(service.listByMachine(user, machineId, pageable));
    }
    return PageResponse.of(service.listAll(user, pageable));
  }
}
