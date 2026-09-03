package com.syncro.maintenance.preventive.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.preventive.api.PreventiveDtos.AssignWorkOrderRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.CompleteWorkOrderRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.GenerateWorkOrdersRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.SweepOverdueView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.WorkOrderView;
import com.syncro.maintenance.preventive.application.PmWorkOrderService;
import com.syncro.maintenance.preventive.domain.PmWorkOrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** PM work order API (story 19-4, blueprint F6). */
@Tag(name = "pm-work-orders")
@Validated
@RestController
@RequestMapping("/api/v1/pm-work-orders")
public class PmWorkOrderController {

  private final PmWorkOrderService workOrders;

  public PmWorkOrderController(PmWorkOrderService workOrders) {
    this.workOrders = workOrders;
  }

  @Operation(operationId = "generatePmWorkOrders", summary = "Generate workorders from an ACTIVE schedule's dates (idempotent per period)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Created workorders (empty when all periods already exist)",
          content = @Content(schema = @Schema(implementation = WorkOrderView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Schedule or machine not found"),
      @ApiResponse(responseCode = "409", description = "Schedule not ACTIVE or period workorder already exists")
  })
  @PostMapping("/generate")
  public List<WorkOrderView> generate(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody GenerateWorkOrdersRequest request) {
    return workOrders.generate(user, request.scheduleId()).stream()
        .map(PmWorkOrderController::toView).toList();
  }

  @Operation(operationId = "assignPmWorkOrder", summary = "Assign a SCHEDULED workorder to a technician (→ ASSIGNED)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Workorder assigned",
          content = @Content(schema = @Schema(implementation = WorkOrderView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder, machine or technician not found"),
      @ApiResponse(responseCode = "409", description = "Invalid transition")
  })
  @PostMapping("/{id}/assign")
  public WorkOrderView assign(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody AssignWorkOrderRequest request) {
    return toView(workOrders.assign(user, id, request.technicianId()));
  }

  @Operation(operationId = "startPmWorkOrder", summary = "Start an ASSIGNED workorder by its assignee (→ IN_PROGRESS)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Workorder started",
          content = @Content(schema = @Schema(implementation = WorkOrderView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden (not the assignee)"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "409", description = "Invalid transition")
  })
  @PostMapping("/{id}/start")
  public WorkOrderView start(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return toView(workOrders.start(user, id));
  }

  @Operation(operationId = "completePmWorkOrder", summary = "Complete an IN_PROGRESS workorder by its assignee (→ COMPLETED)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Workorder completed",
          content = @Content(schema = @Schema(implementation = WorkOrderView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden (not the assignee)"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "409", description = "Invalid transition")
  })
  @PostMapping("/{id}/complete")
  public WorkOrderView complete(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody(required = false) CompleteWorkOrderRequest request) {
    return toView(workOrders.complete(user, id, request != null ? request.certificateUrl() : null));
  }

  @Operation(operationId = "sweepOverduePmWorkOrders", summary = "Mark past-due non-terminal workorders OVERDUE (Clock-based, endpoint-triggered)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Sweep count returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  @PostMapping("/sweep-overdue")
  public SweepOverdueView sweepOverdue(@AuthenticationPrincipal AuthenticatedUser user) {
    return new SweepOverdueView(workOrders.sweepOverdue(user));
  }

  @Operation(operationId = "listPmWorkOrders", summary = "List workorders (filter by status, machineId)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Workorders returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public List<WorkOrderView> list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) PmWorkOrderStatus status,
      @RequestParam(required = false) UUID machineId) {
    return workOrders.list(user, status, machineId).stream()
        .map(PmWorkOrderController::toView).toList();
  }

  @Operation(operationId = "getPmWorkOrder", summary = "Get a workorder")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Workorder returned",
          content = @Content(schema = @Schema(implementation = WorkOrderView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @GetMapping("/{id}")
  public WorkOrderView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
    return toView(workOrders.get(user, id));
  }

  private static WorkOrderView toView(
      com.syncro.maintenance.preventive.application.PmWorkOrderService.WorkOrderView v) {
    return new WorkOrderView(v.id(), v.machineId(), v.templateId(), v.frequencyId(),
        v.frequencyCode(), v.frequencyName(), v.templateRevision(), v.status(),
        v.assignedTechnicianId(), v.scheduledDate(), v.startedAt(), v.completedAt(),
        v.certificateUrl(), v.createdAt(), v.updatedAt());
  }
}
