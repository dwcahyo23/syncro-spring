package com.syncro.maintenance.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.api.WorkOrderDtos.AssignWorkOrderRequest;
import com.syncro.maintenance.api.WorkOrderDtos.CreateWorkOrderRequest;
import com.syncro.maintenance.api.WorkOrderDtos.TransitionWorkOrderRequest;
import com.syncro.maintenance.api.WorkOrderDtos.WorkOrderView;
import com.syncro.maintenance.application.WorkOrderService;
import com.syncro.maintenance.application.WorkOrderService.AssignWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.CreateResult;
import com.syncro.maintenance.application.WorkOrderService.CreateWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.TransitionWorkOrderCommand;
import com.syncro.maintenance.domain.workorder.WorkOrder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/workorders")
public class WorkOrderController {

  private final WorkOrderService workOrders;

  public WorkOrderController(WorkOrderService workOrders) {
    this.workOrders = workOrders;
  }

  @Operation(operationId = "createWorkOrder", summary = "Create an internal workorder")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Idempotent replay — returns the existing workorder"),
      @ApiResponse(responseCode = "201", description = "Workorder created", content = @Content(schema = @Schema(implementation = WorkOrderView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or malformed JSON"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Category, machine or parent not found"),
      @ApiResponse(responseCode = "503", description = "Workorder id sequence exhausted")
  })
  @PostMapping
  public ResponseEntity<WorkOrderView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 64) String idempotencyKey,
      @Valid @RequestBody CreateWorkOrderRequest request) {
    var created = workOrders.create(user, new CreateWorkOrderCommand(request.categoryCode(), request.machineId(),
        request.description(), request.parentId(), idempotencyKey));
    if (created.replay()) {
      return ResponseEntity.ok(toDto(created.workorder()));
    }
    return ResponseEntity.created(URI.create("/api/v1/workorders/" + created.workorder().id()))
        .body(toDto(created.workorder()));
  }

  @Operation(operationId = "assignWorkOrder", summary = "Assign an open workorder to a technician")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Workorder assigned"),
      @ApiResponse(responseCode = "400", description = "Validation or malformed JSON"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or assignee not found"),
      @ApiResponse(responseCode = "409", description = "Invalid state transition or self-assignment")
  })
  @PostMapping("/{id}/assign")
  public WorkOrderView assign(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @Valid @RequestBody AssignWorkOrderRequest request) {
    return toDto(workOrders.assign(user, id, new AssignWorkOrderCommand(request.assigneeUserId())));
  }

  @Operation(operationId = "transitionWorkOrder", summary = "Transition a workorder through its lifecycle")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Workorder transitioned", content = @Content(schema = @Schema(implementation = WorkOrderView.class))),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON or missing override reason"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "409", description = "Invalid state transition, procurement conflict or non-terminal children")
  })
  @PostMapping("/{id}/transition")
  public WorkOrderView transition(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @Valid @RequestBody TransitionWorkOrderRequest request) {
    return toDto(workOrders.transition(user, id,
        new TransitionWorkOrderCommand(request.toStatus(), request.reason(), request.overrideReason())));
  }

  private WorkOrderView toDto(WorkOrder workOrder) {
    return new WorkOrderView(workOrder.id(), workOrder.source(), workOrder.status(), workOrder.categoryId(),
        workOrder.machineId(), workOrder.description(), workOrder.parentId(), workOrder.assignedTechnicianId(),
        workOrder.createdBy(), workOrder.createdAt(), workOrder.updatedAt());
  }
}