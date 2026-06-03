package com.syncro.sparepart.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.sparepart.api.MachineSparepartInstallationDtos.InstallationListResponse;
import com.syncro.sparepart.api.MachineSparepartInstallationDtos.InstallationRequest;
import com.syncro.sparepart.api.MachineSparepartInstallationDtos.InstallationUpdateRequest;
import com.syncro.sparepart.api.MachineSparepartInstallationDtos.InstallationView;
import com.syncro.sparepart.api.MachineSparepartInstallationDtos.TaxonomyRefView;
import com.syncro.sparepart.application.MachineSparepartInstallationService;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationCommand;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationFilters;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationUpdateCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
@RequestMapping("/api/v1/machine-sparepart-installations")
public class MachineSparepartInstallationController {
  private final MachineSparepartInstallationService installations;

  public MachineSparepartInstallationController(MachineSparepartInstallationService installations) {
    this.installations = installations;
  }

  @Operation(operationId = "listMachineSparepartInstallations", summary = "List machine sparepart installations")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Installations returned"),
      @ApiResponse(responseCode = "400", description = "Invalid filter value"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  @GetMapping
  public InstallationListResponse list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID machineId,
      @RequestParam(required = false) UUID sparepartId,
      @RequestParam(required = false) UUID plantId,
      @RequestParam(required = false) UUID machineGroupId,
      @PageableDefault(size = 100, sort = "installedAt") Pageable pageable) {
    var result = installations.list(user, new InstallationFilters(machineId, sparepartId, plantId, machineGroupId), pageable);
    return new InstallationListResponse(result.items().stream().map(this::toDto).toList(), result.totalElements(), result.page(), result.size(), result.sort());
  }

  @Operation(operationId = "getMachineSparepartInstallation", summary = "Get machine sparepart installation")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Installation returned"),
      @ApiResponse(responseCode = "400", description = "Invalid installation id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Installation not found")
  })
  @GetMapping("/{installationId}")
  public InstallationView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID installationId) {
    return toDto(installations.get(user, installationId));
  }

  @Operation(operationId = "createMachineSparepartInstallation", summary = "Create machine sparepart installation")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Installation created", content = @Content(schema = @Schema(implementation = InstallationView.class))),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, invalid machine, or invalid sparepart"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "409", description = "Installation data integrity conflict")
  })
  @PostMapping
  public ResponseEntity<InstallationView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody InstallationRequest request) {
    var created = toDto(installations.create(user, command(request)));
    return ResponseEntity.created(URI.create("/api/v1/machine-sparepart-installations/" + created.id())).body(created);
  }

  @Operation(operationId = "updateMachineSparepartInstallation", summary = "Update machine sparepart installation")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Installation updated"),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, or invalid installation id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Installation not found"),
      @ApiResponse(responseCode = "409", description = "Installation data integrity conflict")
  })
  @PutMapping("/{installationId}")
  public InstallationView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID installationId,
      @Valid @RequestBody InstallationUpdateRequest request) {
    return toDto(installations.update(user, installationId, new InstallationUpdateCommand(
        request.functionName(), request.expectedProductionCount(), request.baselineCounter(), request.thresholdPercentage())));
  }

  @Operation(operationId = "deleteMachineSparepartInstallation", summary = "Delete machine sparepart installation")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Installation deleted", content = @Content),
      @ApiResponse(responseCode = "400", description = "Invalid installation id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Installation not found"),
      @ApiResponse(responseCode = "409", description = "Installation data integrity conflict")
  })
  @DeleteMapping("/{installationId}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID installationId) {
    installations.delete(user, installationId);
    return ResponseEntity.noContent().build();
  }

  private InstallationCommand command(InstallationRequest request) {
    return new InstallationCommand(request.machineId(), request.sparepartId(), request.functionName(), request.expectedProductionCount(), request.baselineCounter(), request.thresholdPercentage());
  }

  private InstallationView toDto(MachineSparepartInstallationService.InstallationView installation) {
    return new InstallationView(installation.id(), installation.machineId(), installation.machineCode(), installation.machineName(),
        installation.plantId(), installation.plantCode(), installation.plantName(), installation.machineGroupId(), installation.machineGroupName(),
        installation.sparepartId(), installation.sparepartCode(), installation.sparepartName(), installation.functionName(), toDto(installation.category()),
        toDto(installation.brand()), toDto(installation.kind()), toDto(installation.type()), installation.expectedProductionCount(),
        installation.baselineCounter(), installation.currentCount(), installation.consumedProductionCount(), installation.consumedPercentage(),
        installation.thresholdPercentage(), installation.calculationBasis(), installation.installedAt(), installation.createdAt(), installation.updatedAt());
  }

  private TaxonomyRefView toDto(MachineSparepartInstallationService.TaxonomyRefView taxonomy) {
    return new TaxonomyRefView(taxonomy.id(), taxonomy.code(), taxonomy.name());
  }
}
