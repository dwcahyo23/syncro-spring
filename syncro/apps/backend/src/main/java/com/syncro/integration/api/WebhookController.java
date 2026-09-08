package com.syncro.integration.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.integration.api.IntegrationDtos.CreateWebhookConfigRequest;
import com.syncro.integration.api.IntegrationDtos.DeliveryListResponse;
import com.syncro.integration.api.IntegrationDtos.UpdateWebhookConfigRequest;
import com.syncro.integration.api.IntegrationDtos.WebhookConfigView;
import com.syncro.integration.application.WebhookConfigService;
import com.syncro.integration.application.WebhookDeliveryQueryService;
import com.syncro.integration.domain.WebhookDeliveryStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SUPER_ADMIN-only webhook config CRUD + delivery-log reads (story 22-1, blueprint I4).
 *
 * <p>No DELETE endpoint (21-1 no-delete precedent) — deactivate via {@code active=false}.
 * The delivery reads live on two paths: per-config under {@code /webhooks/{id}/deliveries}
 * (rides the config's admin_only path) and the global {@code /webhook-deliveries?status=}.
 */
@Validated
@Tag(name = "webhooks")
@RestController
@RequestMapping("/api/v1")
public class WebhookController {

  private final WebhookConfigService configService;
  private final WebhookDeliveryQueryService deliveryService;

  public WebhookController(WebhookConfigService configService,
      WebhookDeliveryQueryService deliveryService) {
    this.configService = configService;
    this.deliveryService = deliveryService;
  }

  @Operation(operationId = "listWebhookConfigs", summary = "List webhook configs (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Configs returned",
          content = @Content(schema = @Schema(implementation = WebhookConfigView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only")
  })
  @GetMapping("/webhooks")
  public List<WebhookConfigView> list(@AuthenticationPrincipal AuthenticatedUser user) {
    return configService.list(user);
  }

  @Operation(operationId = "getWebhookConfig", summary = "Read one webhook config (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Config returned",
          content = @Content(schema = @Schema(implementation = WebhookConfigView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only"),
      @ApiResponse(responseCode = "404", description = "Config not found")
  })
  @GetMapping("/webhooks/{id}")
  public WebhookConfigView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    return configService.get(user, id);
  }

  @Operation(operationId = "createWebhookConfig", summary = "Create a webhook config (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Config created (secret masked)",
          content = @Content(schema = @Schema(implementation = WebhookConfigView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only"),
      @ApiResponse(responseCode = "409", description = "Duplicate config name")
  })
  @PostMapping("/webhooks")
  public ResponseEntity<WebhookConfigView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateWebhookConfigRequest request) {
    var view = configService.create(user, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/webhooks/" + view.id()))
        .body(view);
  }

  @Operation(operationId = "updateWebhookConfig",
      summary = "Update a webhook config / rotate secret / toggle active (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Config updated (secret masked)",
          content = @Content(schema = @Schema(implementation = WebhookConfigView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only"),
      @ApiResponse(responseCode = "404", description = "Config not found")
  })
  @PatchMapping("/webhooks/{id}")
  public WebhookConfigView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
      @Valid @RequestBody UpdateWebhookConfigRequest request) {
    return configService.update(user, id, request);
  }

  @Operation(operationId = "listWebhookDeliveriesForConfig",
      summary = "List deliveries for one config (SUPER_ADMIN, newest first)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Deliveries returned",
          content = @Content(schema = @Schema(implementation = DeliveryListResponse.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only")
  })
  @GetMapping("/webhooks/{id}/deliveries")
  public DeliveryListResponse deliveriesForConfig(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return deliveryService.listForConfig(user, id, page, size);
  }

  @Operation(operationId = "listWebhookDeliveries",
      summary = "List webhook deliveries, optionally filtered by status (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Deliveries returned",
          content = @Content(schema = @Schema(implementation = DeliveryListResponse.class))),
      @ApiResponse(responseCode = "400", description = "Unknown status filter"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only")
  })
  @GetMapping("/webhook-deliveries")
  public DeliveryListResponse deliveries(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) WebhookDeliveryStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return deliveryService.list(user, status, page, size);
  }
}
