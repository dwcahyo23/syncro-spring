package com.syncro.notification.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.notification.api.WahaTemplateDtos.UpsertTemplateRequest;
import com.syncro.notification.api.WahaTemplateDtos.WahaTemplateView;
import com.syncro.notification.application.WahaTemplateService;
import com.syncro.notification.domain.WahaTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notification/templates")
public class WahaTemplateController {

  private final WahaTemplateService templateService;

  public WahaTemplateController(WahaTemplateService templateService) {
    this.templateService = templateService;
  }

  @Operation(operationId = "getActiveWahaTemplate", summary = "Get active WAHA alert message template")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Template returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Template not found")
  })
  @GetMapping
  public WahaTemplateView getActive() {
    WahaTemplate template = templateService.getActiveTemplate();
    return toView(template);
  }

  @Operation(operationId = "upsertWahaTemplate", summary = "Create or update WAHA alert message template")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Template saved"),
      @ApiResponse(responseCode = "400", description = "Invalid template body or unknown variables"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  @PutMapping
  public WahaTemplateView upsert(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody UpsertTemplateRequest request) {
    WahaTemplate template = templateService.upsertTemplate(request.body(), user);
    return toView(template);
  }

  private WahaTemplateView toView(WahaTemplate template) {
    return new WahaTemplateView(
        template.id(),
        template.templateKey(),
        template.body(),
        template.updatedAt());
  }
}
