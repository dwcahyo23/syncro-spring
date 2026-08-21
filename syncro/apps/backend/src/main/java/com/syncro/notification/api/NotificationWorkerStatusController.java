package com.syncro.notification.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.notification.application.NotificationWorkerStatus;
import com.syncro.notification.application.NotificationWorkerStatusService;
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
 * Notification worker health status — SUPER_ADMIN only.
 *
 * <p>Deliberately NOT exposed via {@code /actuator/health}: that endpoint is unauthenticated
 * (k8s liveness) while worker state must be visible only to SUPER_ADMIN (NFR-006).
 */
@Tag(name = "notification-worker")
@RestController
@RequestMapping("/api/v1/notification/worker")
public class NotificationWorkerStatusController {

  private final NotificationWorkerStatusService statusService;

  public NotificationWorkerStatusController(NotificationWorkerStatusService statusService) {
    this.statusService = statusService;
  }

  @Operation(operationId = "getNotificationWorkerStatus", summary = "Notification worker status")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Notification worker status returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only")
  })
  @GetMapping("/status")
  public NotificationWorkerStatus status(@AuthenticationPrincipal AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return statusService.status();
  }
}
