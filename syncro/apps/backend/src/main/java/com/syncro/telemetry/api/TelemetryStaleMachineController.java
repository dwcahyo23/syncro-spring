package com.syncro.telemetry.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.telemetry.application.StaleMachineStatus;
import com.syncro.telemetry.application.TelemetryStaleMachineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-machine stale-telemetry evidence — SUPER_ADMIN only.
 *
 * <p>Deliberately NOT exposed via {@code /actuator/health}: that endpoint is unauthenticated
 * (k8s liveness) while machine-level evidence must be visible only to SUPER_ADMIN (NFR-006),
 * mirroring {@link TelemetryFreshnessController}.
 */
@Tag(name = "telemetry-stale-machines")
@RestController
@RequestMapping("/api/v1/telemetry/stale-machines")
public class TelemetryStaleMachineController {

  private final TelemetryStaleMachineService staleMachineService;

  public TelemetryStaleMachineController(TelemetryStaleMachineService staleMachineService) {
    this.staleMachineService = staleMachineService;
  }

  @Operation(operationId = "getStaleMachines",
      summary = "Active machines with stale telemetry (evidence for the health dashboard)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Stale machine evidence returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only")
  })
  @GetMapping
  public StaleMachineStatus staleMachines(@AuthenticationPrincipal AuthenticatedUser user) {
    if (user == null || user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return staleMachineService.staleMachines(user);
  }
}
