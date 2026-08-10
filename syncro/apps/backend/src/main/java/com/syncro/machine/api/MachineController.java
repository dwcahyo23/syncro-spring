package com.syncro.machine.api;

import com.syncro.audit.application.AuditLogService;
import com.syncro.audit.application.AuditLogService.AuditLogQuery;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.machine.api.MachineDtos.MachineListResponse;
import com.syncro.machine.api.MachineDtos.MachineRequest;
import com.syncro.machine.api.MachineDtos.MachineView;
import com.syncro.machine.application.MachineService;
import com.syncro.machine.application.MachineService.MachineCommand;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.sparepart.application.MachineSparepartInstallationService;
import com.syncro.telemetry.application.LatestTelemetryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
  private final LatestTelemetryQueryService telemetryQuery;

  public MachineController(MachineService machines, LatestTelemetryQueryService telemetryQuery, MachineSparepartInstallationService sparepartInstallations, AuditLogService auditLog) {
    this.machines = machines;
    this.telemetryQuery = telemetryQuery;
    this.sparepartInstallations = sparepartInstallations;
    this.auditLog = auditLog;
  }

  private final MachineService machines;
  private final LatestTelemetryQueryService telemetryQuery;
  private final MachineSparepartInstallationService sparepartInstallations;
  private final AuditLogService auditLog;

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
      @RequestParam(required = false) MachineStatus status,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer limit,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int size,
      @RequestParam(defaultValue = "code,asc") String sort) {
    var result = machines.list(user, plantId, machineGroupId, status, search, page, limit == null ? size : limit, sort);
    var hydratedItems = result.items().stream()
        .map(this::hydrateWithLatestTelemetry)
        .toList();
    return new MachineListResponse(hydratedItems, result.totalElements(), result.page(), result.size(), result.sort());
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
    return hydrateWithLatestTelemetry(machines.get(user, machineId));
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

  @Operation(operationId = "getMachineByCode", summary = "Get machine by code")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Machine returned"),
      @ApiResponse(responseCode = "400", description = "Invalid machine code"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine not found")
  })
  @GetMapping("/code/{machineCode}")
  public MachineView getByCode(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String machineCode) {
    return hydrateWithLatestTelemetry(machines.getByCode(user, machineCode));
  }

  @Operation(operationId = "listMachineSpareparts", summary = "List spareparts for a machine")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Spareparts returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine not found")
  })
  @GetMapping("/{machineId}/spareparts")
  public ResponseEntity<Map<String, Object>> getSpareparts(@AuthenticationPrincipal AuthenticatedUser user, 
                                                            @PathVariable UUID machineId) {
    var result = sparepartInstallations.list(user, new MachineSparepartInstallationService.InstallationFilters(machineId, null, null, null), org.springframework.data.domain.Pageable.unpaged());
    return ResponseEntity.ok(Map.of("items", result.items().stream().map(this::toSparepartDto).toList()));
  }

  private Map<String, Object> toSparepartDto(MachineSparepartInstallationService.InstallationView installation) {
    return Map.of(
        "installationId", installation.id().toString(),
        "sparepartCode", installation.sparepartCode(),
        "sparepartName", installation.sparepartName(),
        "functionName", installation.functionName(),
        "lifetimeHours", installation.lifetimeHours(),
        "installedAt", installation.installedAt().toString(),
        "isRemoved", installation.isRemoved()
    );
  }

  @Operation(operationId = "listMachineAuditLogEntries", summary = "List audit log entries for a machine")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Audit log entries returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Machine not found")
  })
  @GetMapping("/{machineId}/audit-log")
  public ResponseEntity<Map<String, Object>> getAuditLog(@AuthenticationPrincipal AuthenticatedUser user,
                                                          @PathVariable UUID machineId,
                                                          @RequestParam(defaultValue = "50") int pageSize) {
    var result = auditLog.list(user, new AuditLogQuery(com.syncro.audit.domain.AuditEntityType.MACHINE, null, null, null, null, 0, pageSize, "createdAt,desc"));
    return ResponseEntity.ok(Map.of("items", result.items().stream().map(this::toAuditEntryDto).toList()));
  }

  private Map<String, Object> toAuditEntryDto(com.syncro.audit.api.AuditLogDtos.AuditLogEntryView entry) {
    return Map.of(
        "id", entry.id().toString(),
        "action", entry.action().name(),
        "entityType", entry.entityType().name(),
        "entityLabel", entry.entityLabel(),
        "actorName", entry.actorName(),
        "timestamp", entry.createdAt().toString(),
        "previousValue", entry.previousValue(),
        "newValue", entry.newValue()
    );
  }

  private static MachineCommand command(MachineRequest request) {
    return new MachineCommand(request.plantId(), request.machineGroupId(), request.code(), request.name(), request.status(),
        request.brand(), request.installedAt(), request.notes(), request.optionalTelemetryFields());
  }

  private MachineView toDto(MachineService.MachineView machine) {
    return new MachineView(machine.id(), machine.plantId(), machine.plantCode(), machine.plantName(), machine.machineGroupId(),
        machine.machineGroupName(), machine.code(), machine.name(), machine.status(), machine.brand(), machine.installedAt(),
        machine.notes(), machine.createdAt(), machine.updatedAt(), machine.optionalTelemetryFields(), null);
  }

  private MachineView hydrateWithLatestTelemetry(MachineService.MachineView machine) {
    var telemetry = telemetryQuery.latestTelemetry(machine.id(), machine.status());
    return new MachineView(machine.id(), machine.plantId(), machine.plantCode(), machine.plantName(), machine.machineGroupId(),
        machine.machineGroupName(), machine.code(), machine.name(), machine.status(), machine.brand(), machine.installedAt(),
        machine.notes(), machine.createdAt(), machine.updatedAt(), machine.optionalTelemetryFields(), telemetry);
  }
}
