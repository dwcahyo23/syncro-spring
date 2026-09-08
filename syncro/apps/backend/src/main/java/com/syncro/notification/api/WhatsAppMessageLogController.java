package com.syncro.notification.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.notification.api.WhatsAppMessageLogDtos.MessageLogListResponse;
import com.syncro.notification.application.WhatsAppMessageLogQueryService;
import com.syncro.notification.domain.WhatsAppMessageLogStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SUPER_ADMIN-only paged reads over the outbound WhatsApp message log
 * (story 22-4, AC3). Evidence view only — no mutations, no frontend UI this story.
 * The service gate and the rego {@code admin_only_paths} entry move together.
 */
@Validated
@Tag(name = "whatsapp-message-logs")
@RestController
@RequestMapping("/api/v1")
public class WhatsAppMessageLogController {

  private final WhatsAppMessageLogQueryService queryService;

  public WhatsAppMessageLogController(WhatsAppMessageLogQueryService queryService) {
    this.queryService = queryService;
  }

  @Operation(operationId = "listWhatsAppMessageLogs",
      summary = "List WhatsApp message logs, newest-first, filterable (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Logs returned (masked projections)",
          content = @Content(schema = @Schema(implementation = MessageLogListResponse.class))),
      @ApiResponse(responseCode = "400", description = "Unknown status filter"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only")
  })
  @GetMapping("/whatsapp-message-logs")
  public MessageLogListResponse list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) WhatsAppMessageLogStatus status,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String workOrderId,
      @RequestParam(required = false) String traceId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return queryService.list(user, status, targetType, workOrderId, traceId, page, size);
  }
}
