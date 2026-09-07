package com.syncro.compliance.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.compliance.application.MachineSetupBaselineService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Machine setup baseline REST surface (story 21-3, blueprint H5):
 * {@code /api/v1/machine-setup-baselines} list (machine + active-only filters) /
 * create / get plus the {@code /{id}/activate} pointer flip. Controllers only
 * bind/validate and delegate — version assignment, supersede, scope filtering,
 * and audit live in {@link MachineSetupBaselineService} (spine API rule).
 */
@RestController
@RequestMapping("/api/v1/machine-setup-baselines")
public class MachineSetupBaselineController {

  private final MachineSetupBaselineService baselines;

  public MachineSetupBaselineController(MachineSetupBaselineService baselines) {
    this.baselines = baselines;
  }

  @Operation(operationId = "listMachineSetupBaselines",
      summary = "List setup baselines visible in the caller's machine scope "
          + "(optional ?machineId= and ?activeOnly=true filters)")
  @GetMapping
  public List<MachineSetupBaselineService.BaselineView> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID machineId,
      @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
    return baselines.list(user, machineId, activeOnly);
  }

  @Operation(operationId = "createMachineSetupBaseline",
      summary = "Record a setup baseline (server-assigned version, supersedes siblings)")
  @PostMapping
  public ResponseEntity<MachineSetupBaselineService.BaselineView> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody ComplianceDtos.CreateBaselineRequest request) {
    var view = baselines.create(user, new MachineSetupBaselineService.CreateBaselineCommand(
        request.machineId(), request.ecnId(), request.parameters()));
    return ResponseEntity.created(URI.create("/api/v1/machine-setup-baselines/" + view.id()))
        .body(view);
  }

  @Operation(operationId = "getMachineSetupBaseline",
      summary = "Read one baseline (machine-scope filtered)")
  @GetMapping("/{id}")
  public MachineSetupBaselineService.BaselineView get(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    return baselines.get(user, id);
  }

  @Operation(operationId = "activateMachineSetupBaseline",
      summary = "Activate a specific version (deactivates the machine's siblings)")
  @PostMapping("/{id}/activate")
  public MachineSetupBaselineService.BaselineView activate(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    return baselines.activate(user, id);
  }
}
