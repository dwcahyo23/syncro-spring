package com.syncro.telemetry.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.telemetry.application.TelemetryDataQualityService;
import com.syncro.telemetry.application.TelemetryDataQualityStatus;
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
 * Telemetry data-quality status — SUPER_ADMIN only.
 *
 * <p>Deliberately NOT exposed via {@code /actuator/health}: that endpoint is unauthenticated
 * (k8s liveness) while data-quality metrics must be visible only to SUPER_ADMIN (NFR-006).
 */
@Tag(name = "telemetry-data-quality")
@RestController
@RequestMapping("/api/v1/telemetry/data-quality")
public class TelemetryDataQualityController {

  private final TelemetryDataQualityService dataQualityService;

  public TelemetryDataQualityController(TelemetryDataQualityService dataQualityService) {
    this.dataQualityService = dataQualityService;
  }

  @Operation(operationId = "getTelemetryDataQuality", summary = "Telemetry data-quality status")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Telemetry data-quality status returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only")
  })
  @GetMapping
  public TelemetryDataQualityStatus dataQuality(@AuthenticationPrincipal AuthenticatedUser user) {
    if (user == null || user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return dataQualityService.status();
  }
}
