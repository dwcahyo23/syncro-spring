package com.syncro.authz.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.authz.api.AuthzDtos.AllowedActionsView;
import com.syncro.authz.application.PolicyDecisionPoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/authz")
public class AuthzController {

  private final PolicyDecisionPoint policyDecisionPoint;

  public AuthzController(PolicyDecisionPoint policyDecisionPoint) {
    this.policyDecisionPoint = policyDecisionPoint;
  }

  @Operation(operationId = "getAllowedActions", summary = "List actions the current user is allowed to perform")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Allowed actions returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping("/allowed-actions")
  public AllowedActionsView allowedActions(@AuthenticationPrincipal AuthenticatedUser user) {
    var resolved = policyDecisionPoint.resolvedActions(user);
    return new AllowedActionsView(resolved.actions(), resolved.degraded());
  }
}
