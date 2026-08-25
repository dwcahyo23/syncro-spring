package com.syncro.authz.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.authz.api.AuthzDtos.AllowedActionsView;
import com.syncro.authz.api.AuthzDtos.AuthzDecisionsPageView;
import com.syncro.authz.application.DecisionLogService;
import com.syncro.authz.application.PolicyDecisionPoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/authz")
public class AuthzController {

  private final PolicyDecisionPoint policyDecisionPoint;
  private final DecisionLogService decisionLogs;

  public AuthzController(PolicyDecisionPoint policyDecisionPoint, DecisionLogService decisionLogs) {
    this.policyDecisionPoint = policyDecisionPoint;
    this.decisionLogs = decisionLogs;
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

  @Operation(operationId = "getAuthzDecisions", summary = "List persisted OPA enforcement decisions")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Decisions returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN or AUDITOR only")
  })
  @GetMapping("/decisions")
  public AuthzDecisionsPageView decisions(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    if (user == null || (user.applicationRole() != ApplicationRole.SUPER_ADMIN
        && user.applicationRole() != ApplicationRole.AUDITOR)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return decisionLogs.list(page, size);
  }
}
