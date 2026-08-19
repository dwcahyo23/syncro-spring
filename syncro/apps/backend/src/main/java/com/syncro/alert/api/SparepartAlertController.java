package com.syncro.alert.api;

import com.syncro.alert.api.SparepartAlertDtos.AlertListResponse;
import com.syncro.alert.api.SparepartAlertDtos.AlertView;
import com.syncro.alert.application.SparepartAlertQueryService;
import com.syncro.alert.application.SparepartAlertQueryService.AlertDetailView;
import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
public class SparepartAlertController {

  private final SparepartAlertQueryService alertQuery;

  public SparepartAlertController(SparepartAlertQueryService alertQuery) {
    this.alertQuery = alertQuery;
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

  @Operation(operationId = "getAlert", summary = "Get sparepart lifetime alert")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Alert returned",
          content = @Content(schema = @Schema(implementation = AlertView.class))),
      @ApiResponse(responseCode = "400", description = "Invalid alert id"),
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
