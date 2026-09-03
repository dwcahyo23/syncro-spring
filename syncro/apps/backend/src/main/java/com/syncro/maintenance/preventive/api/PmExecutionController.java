package com.syncro.maintenance.preventive.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ExecutionItemView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ExecutionView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.FillExecutionItemRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.StartExecutionRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.VerifyExecutionRequest;
import com.syncro.maintenance.preventive.application.PmExecutionService;
import com.syncro.maintenance.preventive.application.PmExecutionService.FillExecutionCommand;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** PM execution API (story 19-5, blueprint F7/F8). */
@Tag(name = "pm-executions")
@Validated
@RestController
@RequestMapping("/api/v1/pm-executions")
public class PmExecutionController {

  private final PmExecutionService executions;

  public PmExecutionController(PmExecutionService executions) {
    this.executions = executions;
  }

  @Operation(operationId = "startPmExecution", summary = "Start an execution on an IN_PROGRESS PM work order (one per work order)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Execution started",
          content = @Content(schema = @Schema(implementation = ExecutionView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden (not the assignee)"),
      @ApiResponse(responseCode = "404", description = "PM work order not found"),
      @ApiResponse(responseCode = "409", description = "Work order not IN_PROGRESS or execution already exists")
  })
  @PostMapping("/start")
  public ResponseEntity<ExecutionView> start(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody StartExecutionRequest request) {
    var view = executions.start(user, request.pmWoId());
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/pm-executions/" + view.id()))
        .body(toView(view));
  }

  @Operation(operationId = "fillPmExecutionItem", summary = "Fill one checklist item's result (snapshot + MEASUREMENT/OK_NG outcome)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Item filled",
          content = @Content(schema = @Schema(implementation = ExecutionItemView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden (not the execution's technician)"),
      @ApiResponse(responseCode = "404", description = "Execution or checklist item not found"),
      @ApiResponse(responseCode = "409", description = "Execution completed")
  })
  @PostMapping("/{id}/items/{itemId}/fill")
  public ExecutionItemView fill(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @PathVariable UUID itemId,
      @Valid @RequestBody FillExecutionItemRequest request) {
    return toItemView(executions.fill(user, id, itemId, new FillExecutionCommand(
        request.actualValue(), request.ok(), request.ng(), request.ngNotes(),
        request.ngPhotoUrl(), Boolean.TRUE.equals(request.blocked()),
        request.blockingWoCode())));
  }

  @Operation(operationId = "completePmExecution", summary = "Complete an execution (NG rollup, work order COMPLETED, schedule date EXECUTED, finding work order for critical NG)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Execution completed",
          content = @Content(schema = @Schema(implementation = ExecutionView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden (not the execution's technician)"),
      @ApiResponse(responseCode = "404", description = "Execution not found"),
      @ApiResponse(responseCode = "409", description = "No items filled or already completed")
  })
  @PostMapping("/{id}/complete")
  public ExecutionView complete(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return toView(executions.complete(user, id));
  }

  @Operation(operationId = "verifyPmExecution", summary = "SPV sign-off of a completed execution (leader-gated)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Execution verified",
          content = @Content(schema = @Schema(implementation = ExecutionView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden (not a leader or out of scope)"),
      @ApiResponse(responseCode = "404", description = "Execution not found"),
      @ApiResponse(responseCode = "409", description = "Not completed or already verified")
  })
  @PostMapping("/{id}/verify")
  public ExecutionView verify(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) VerifyExecutionRequest request) {
    return toView(executions.verify(user, id, request != null ? request.spvSignatureId() : null));
  }

  @Operation(operationId = "listPmExecutions", summary = "List executions (filter by pmWoId, technicianId)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Executions returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public List<ExecutionView> list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID pmWoId,
      @RequestParam(required = false) UUID technicianId) {
    return executions.list(user, pmWoId, technicianId).stream()
        .map(PmExecutionController::toView).toList();
  }

  @Operation(operationId = "getPmExecution", summary = "Get an execution with its items")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Execution returned",
          content = @Content(schema = @Schema(implementation = ExecutionView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Execution not found")
  })
  @GetMapping("/{id}")
  public ExecutionView get(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return toView(executions.get(user, id));
  }

  private static ExecutionView toView(
      com.syncro.maintenance.preventive.application.PmExecutionService.ExecutionView v) {
    return new ExecutionView(v.id(), v.pmWoId(), v.scheduleDateId(), v.technicianId(),
        v.spvVerifierId(), v.technicianSignatureId(), v.technicianSignedAt(), v.spvSignatureId(),
        v.spvSignedAt(), v.startedAt(), v.completedAt(), v.hasNgItems(), v.ngCount(),
        v.findingWoId(), v.createdAt(), v.updatedAt(),
        v.items().stream().map(PmExecutionController::toItemView).toList());
  }

  private static ExecutionItemView toItemView(
      com.syncro.maintenance.preventive.application.PmExecutionService.ExecutionItemView v) {
    return new ExecutionItemView(v.id(), v.executionId(), v.checklistItemId(), v.sequence(),
        v.categoryName(), v.parameterText(), v.checkMethod(), v.inputType(), v.isCriticalFlag(),
        v.unit(), v.lsl(), v.nominal(), v.usl(), v.actualValue(), v.isOk(), v.isNg(),
        v.ngNotes(), v.ngPhotoUrl(), v.isBlocked(), v.blockedWoCode(), v.blockingWoId(),
        v.filledAt(), v.createdAt(), v.updatedAt());
  }
}
