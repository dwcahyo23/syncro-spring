package com.syncro.alert.api;

import com.syncro.alert.api.SparepartAlertDtos.AcknowledgeRequest;
import com.syncro.alert.api.SparepartAlertDtos.AlertListResponse;
import com.syncro.alert.api.SparepartAlertDtos.AlertView;
import com.syncro.alert.api.SparepartAlertDtos.ResolveOverrideRequest;
import com.syncro.alert.api.SparepartAlertDtos.ResolveRequest;
import com.syncro.alert.application.SparepartAlertCommandService;
import com.syncro.alert.application.SparepartAlertQueryService;
import com.syncro.alert.application.SparepartAlertQueryService.AlertDetailView;
import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.notification.api.NotificationHistoryDtos.AlertNotificationHistoryResponse;
import com.syncro.notification.application.NotificationHistoryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
public class SparepartAlertController {

  private final SparepartAlertQueryService alertQuery;
  private final SparepartAlertCommandService alertCommand;
  private final NotificationHistoryQueryService notificationHistoryQuery;

  public SparepartAlertController(SparepartAlertQueryService alertQuery,
      SparepartAlertCommandService alertCommand,
      NotificationHistoryQueryService notificationHistoryQuery) {
    this.alertQuery = alertQuery;
    this.alertCommand = alertCommand;
    this.notificationHistoryQuery = notificationHistoryQuery;
  }

  @Operation(operationId = "listAlerts", summary = "List sparepart lifetime alerts")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Alerts returned"),
      @ApiResponse(responseCode = "400", description = "Invalid query parameter"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  @GetMapping
  public AlertListResponse list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID machineId,
      @RequestParam(required = false) UUID plantId,
      @RequestParam(required = false) SparepartAlertStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    var result = alertQuery.list(user, machineId, plantId, status, page, size, sort);
    return new AlertListResponse(
        result.items().stream().map(this::toDto).toList(),
        result.totalElements(),
        result.page(),
        result.size(),
        result.sort());
  }

  @Operation(operationId = "getAlert", summary = "Get a sparepart lifetime alert by ID")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Alert returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Alert not found")
  })
  @GetMapping("/{alertId}")
  public AlertView get(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID alertId) {
    return toDto(alertQuery.get(user, alertId));
  }

  @Operation(operationId = "acknowledgeAlert", summary = "Acknowledge an OPEN alert")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Alert acknowledged"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Alert not found"),
      @ApiResponse(responseCode = "409", description = "Invalid state transition")
  })
  @PostMapping("/{alertId}/acknowledge")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void acknowledge(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID alertId,
      @RequestBody(required = false) AcknowledgeRequest body) {
    var reason = body != null ? body.reason() : null;
    alertCommand.acknowledge(user, alertId, reason);
  }

  @Operation(operationId = "resolveAlert", summary = "Resolve an ACKNOWLEDGED alert")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Alert resolved"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Alert not found"),
      @ApiResponse(responseCode = "409", description = "Invalid state transition")
  })
  @PostMapping("/{alertId}/resolve")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resolve(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID alertId,
      @RequestBody(required = false) ResolveRequest body) {
    var reason = body != null ? body.reason() : null;
    alertCommand.resolve(user, alertId, reason);
  }

  @Operation(operationId = "resolveAlertOverride", summary = "SUPER_ADMIN: resolve an OPEN alert directly without acknowledging")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Alert resolved"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only"),
      @ApiResponse(responseCode = "404", description = "Alert not found"),
      @ApiResponse(responseCode = "409", description = "Invalid state transition")
  })
  @PostMapping("/{alertId}/resolve-override")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resolveOverride(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID alertId,
      @RequestBody(required = false) ResolveOverrideRequest body) {
    var reason = body != null ? body.reason() : null;
    alertCommand.resolveOverride(user, alertId, reason);
  }

   @Operation(operationId = "getAlertNotifications", summary = "Get notification history for an alert")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Notification history returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Alert not found")
  })
  @GetMapping("/{alertId}/notifications")
  public AlertNotificationHistoryResponse getAlertNotifications(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID alertId) {
    return notificationHistoryQuery.getHistory(user, alertId);
  }

  private AlertView toDto(AlertDetailView view) {
    return new AlertView(
        view.id(),
        view.machineId(),
        view.machineCode(),
        view.machineName(),
        view.plantId(),
        view.plantCode(),
        view.plantName(),
        view.machineGroupId(),
        view.machineGroupName(),
        view.installationId(),
        view.sparepartId(),
        view.sparepartCode(),
        view.sparepartName(),
        view.functionName(),
        view.thresholdPercentage(),
        view.baselineCounter(),
        view.expectedProductionCount(),
        view.currentCounterSnapshot(),
        view.consumedProductionCountSnapshot(),
        view.consumedPercentageSnapshot(),
        view.status(),
        view.statusReason(),
        view.traceId(),
        view.createdAt(),
        view.updatedAt());
  }
}
