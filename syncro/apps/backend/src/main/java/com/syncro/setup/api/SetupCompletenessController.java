package com.syncro.setup.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.setup.api.SetupCompletenessDtos.SetupCompletenessResponse;
import com.syncro.setup.application.SetupCompletenessService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/setup-completeness")
public class SetupCompletenessController {

  private final SetupCompletenessService service;

  public SetupCompletenessController(SetupCompletenessService service) {
    this.service = service;
  }

  @Operation(operationId = "getSetupCompleteness", summary = "Get setup completeness checklist")
  @GetMapping
  public SetupCompletenessResponse get(@AuthenticationPrincipal AuthenticatedUser user) {
    return service.get(user);
  }
}
