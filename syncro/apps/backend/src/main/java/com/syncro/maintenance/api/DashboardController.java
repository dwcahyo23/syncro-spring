package com.syncro.maintenance.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.api.DashboardDtos.MachineDashboardResponse;
import com.syncro.maintenance.api.DashboardDtos.PreventiveDashboardResponse;
import com.syncro.maintenance.api.DashboardDtos.WorkorderDashboardResponse;
import com.syncro.maintenance.application.DashboardService;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard read surface (story 14-1, FR-170/FR-171/FR-172): three thin scope-aware
 * GET endpoints. Every count is backend-computed by {@link DashboardService}; the
 * frontend renders only. Optional filters: {@code plantId} on all three;
 * {@code sectionId}/{@code status}/{@code categoryCode} additionally on the workorder
 * dashboard. An out-of-scope filter value yields an empty payload (never a 403,
 * never out-of-scope rows).
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

  private final DashboardService dashboards;

  public DashboardController(DashboardService dashboards) {
    this.dashboards = dashboards;
  }

  @Operation(operationId = "getMachineDashboard", summary = "Machine dashboard: per-machine status, telemetry freshness, open workorders/alerts, lifetime risk")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Machine dashboard returned",
          content = @Content(schema = @Schema(implementation = MachineDashboardResponse.class))),
      @ApiResponse(responseCode = "400", description = "Invalid filter value"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping("/machines")
  public MachineDashboardResponse machines(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID plantId) {
    return dashboards.machineDashboard(user, plantId);
  }

  @Operation(operationId = "getWorkorderDashboard", summary = "Workorder dashboard: counts by status and category, filterable by plant/section/status/category")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Workorder dashboard returned",
          content = @Content(schema = @Schema(implementation = WorkorderDashboardResponse.class))),
      @ApiResponse(responseCode = "400", description = "Invalid filter value"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping("/workorders")
  public WorkorderDashboardResponse workorders(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID plantId,
      @RequestParam(required = false) UUID sectionId,
      @RequestParam(required = false) WorkOrderStatus status,
      @RequestParam(required = false) String categoryCode) {
    return dashboards.workorderDashboard(user, plantId, sectionId, status, categoryCode);
  }

  @Operation(operationId = "getPreventiveDashboard", summary = "Preventive dashboard: due/overdue counts and upcoming schedules")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Preventive dashboard returned",
          content = @Content(schema = @Schema(implementation = PreventiveDashboardResponse.class))),
      @ApiResponse(responseCode = "400", description = "Invalid filter value"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping("/preventive")
  public PreventiveDashboardResponse preventive(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID plantId) {
    return dashboards.preventiveDashboard(user, plantId);
  }
}