package com.syncro.telemetry.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.telemetry.application.TelemetryFreshnessService;
import com.syncro.telemetry.application.TelemetryFreshnessStatus;
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
 * Telemetry freshness status — SUPER_ADMIN only.
 *
 * <p>Deliberately NOT exposed via {@code /actuator/health}: that endpoint is unauthenticated
 * (k8s liveness) while telemetry freshness must be visible only to SUPER_ADMIN (NFR-006).
 */
@Tag(name = "telemetry-freshness")
@RestController
@RequestMapping("/api/v1/telemetry/freshness")
public class TelemetryFreshnessController {

  private final TelemetryFreshnessService freshnessService;

  public TelemetryFreshnessController(TelemetryFreshnessService freshnessService) {
    this.freshnessService = freshnessService;
  }

  @Operation(operationId = "getTelemetryFreshness", summary = "Telemetry freshness status")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Telemetry freshness status returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only")
  })
  @GetMapping
  public TelemetryFreshnessStatus freshness(@AuthenticationPrincipal AuthenticatedUser user) {
    if (user == null || user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return freshnessService.freshness();
  }
}
