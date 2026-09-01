package com.syncro.maintenance.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.api.WorkOrderDtos.AssignTodoRequest;
import com.syncro.maintenance.api.WorkOrderDtos.AssignWorkOrderRequest;
import com.syncro.maintenance.api.WorkOrderDtos.CreateTodoRequest;
import com.syncro.maintenance.api.WorkOrderDtos.CreateWorkOrderRequest;
import com.syncro.maintenance.api.WorkOrderDtos.KanbanView;
import com.syncro.maintenance.api.WorkOrderDtos.ReorderTodoRequest;
import com.syncro.maintenance.api.WorkOrderDtos.RepairSessionView;
import com.syncro.maintenance.api.WorkOrderDtos.RepairSessionsView;
import com.syncro.maintenance.api.WorkOrderDtos.StartSessionRequest;
import com.syncro.maintenance.api.WorkOrderDtos.TodoView;
import com.syncro.maintenance.api.WorkOrderDtos.TransitionWorkOrderRequest;
import com.syncro.maintenance.api.WorkOrderDtos.WorkorderAttachmentView;
import com.syncro.maintenance.api.WorkOrderDtos.WorkorderAttachmentsView;
import com.syncro.maintenance.api.WorkOrderDtos.SaveWorkOrderReportRequest;
import com.syncro.maintenance.api.WorkOrderDtos.WorkOrderReportView;
import com.syncro.maintenance.api.WorkOrderDtos.WorkOrderView;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.maintenance.application.WorkOrderAckService;
import jakarta.servlet.http.HttpServletRequest;
import com.syncro.maintenance.application.WorkOrderAckService.AckResult;
import com.syncro.maintenance.application.WorkOrderAckService.TaskListView;
import com.syncro.maintenance.application.WorkOrderEvidenceService;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceCommand;
import com.syncro.maintenance.application.WorkOrderListService;
import com.syncro.maintenance.application.WorkOrderListService.Page;
import com.syncro.maintenance.application.WorkOrderListService.WorkOrderListView;
import com.syncro.maintenance.application.WorkOrderReportService;
import com.syncro.maintenance.application.WorkOrderReportService.CpkPdfCommand;
import com.syncro.maintenance.application.WorkOrderReportService.SaveReportCommand;
import com.syncro.maintenance.application.WorkAssignmentService;
import com.syncro.maintenance.application.WorkAssignmentService.AssignWorkAssignmentCommand;
import com.syncro.maintenance.application.WorkAssignmentService.WorkAssignmentView;
import com.syncro.maintenance.application.WorkOrderService;
import com.syncro.maintenance.application.WorkOrderService.AssignWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.CreateResult;
import com.syncro.maintenance.application.WorkOrderService.CreateWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.RepairSessionsResult;
import com.syncro.maintenance.application.WorkOrderService.StartSessionCommand;
import com.syncro.maintenance.application.WorkOrderService.TransitionWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderRatingService;
import com.syncro.maintenance.api.WorkorderPrintReportDtos.WorkorderPrintReportView;
import com.syncro.maintenance.application.WorkorderPrintReportService;
import com.syncro.maintenance.application.WorkorderSignatureService;
import com.syncro.maintenance.application.WorkorderSignatureService.ApproveSignatureCommand;
import com.syncro.maintenance.application.WorkorderSignatureService.SignatureResult;
import com.syncro.maintenance.application.WorkOrderTodoService;
import com.syncro.maintenance.application.WorkOrderTodoService.CreateTodoCommand;
import com.syncro.maintenance.application.WorkLogService;
import com.syncro.maintenance.application.WorkLogService.CreateWorkLogCommand;
import com.syncro.maintenance.application.WorkLogService.UpdateWorkLogCommand;
import com.syncro.maintenance.application.WorkLogRatingService;
import com.syncro.maintenance.application.WorkLogRatingService.CreateCriterionCommand;
import com.syncro.maintenance.application.WorkLogRatingService.UpdateCriterionCommand;
import com.syncro.maintenance.application.WorkOrderQualityRatingService;
import com.syncro.maintenance.application.WorkOrderQualityRatingService.SubmitQualityRatingCommand;
import com.syncro.maintenance.domain.workorder.RepairSession;
import com.syncro.maintenance.domain.workorder.WorkOrder;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.domain.workorder.WorkOrderTodo;
import com.syncro.maintenance.domain.workorder.WorkorderRating;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/workorders")
public class WorkOrderController {

  private final WorkOrderService workOrders;
  private final WorkOrderEvidenceService evidence;
  private final WorkOrderReportService report;
  private final WorkOrderTodoService todos;
  private final WorkOrderRatingService ratings;
  private final WorkOrderListService lists;
  private final WorkorderPrintReportService printReports;
  private final WorkorderSignatureService signatures;
  private final WorkOrderAckService acks;
  private final WorkAssignmentService workAssignments;
  private final WorkLogService workLogs;
  private final WorkLogRatingService workLogRatings;
  private final WorkOrderQualityRatingService qualityRatings;

  public WorkOrderController(WorkOrderService workOrders, WorkOrderEvidenceService evidence,
      WorkOrderReportService report, WorkOrderTodoService todos, WorkOrderRatingService ratings,
      WorkOrderListService lists, WorkorderPrintReportService printReports,
      WorkorderSignatureService signatures, WorkOrderAckService acks,
      WorkAssignmentService workAssignments, WorkLogService workLogs, WorkLogRatingService workLogRatings,
      WorkOrderQualityRatingService qualityRatings) {
    this.workOrders = workOrders;
    this.evidence = evidence;
    this.report = report;
    this.todos = todos;
    this.ratings = ratings;
    this.lists = lists;
    this.printReports = printReports;
    this.signatures = signatures;
    this.acks = acks;
    this.workAssignments = workAssignments;
    this.workLogs = workLogs;
    this.workLogRatings = workLogRatings;
    this.qualityRatings = qualityRatings;
  }

  /**
   * Server-paginated workorder list (workorder-table story). Declared before every
   * {@code /{id}...} mapping so Spring never routes {@code GET /api/v1/workorders} into
   * a path-variable handler. Filters: {@code from}/{@code to} ISO dates (inclusive),
   * {@code status}, {@code machineId}, {@code search} (id / machine code-name / category
   * label, case-insensitive); {@code page} 0-based, {@code size} 1..200 (default 20).
   */
  @Operation(operationId = "listWorkorders", summary = "Server-paginated workorder list")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Paginated workorder list returned",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkOrderPageView.class))),
      @ApiResponse(responseCode = "400", description = "Invalid page, size, or date parameter"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public WorkOrderDtos.WorkOrderPageView list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "status", required = false) WorkOrderStatus status,
      @RequestParam(name = "machineId", required = false) UUID machineId,
      @RequestParam(name = "categoryCode", required = false) String categoryCode,
      @RequestParam(name = "search", required = false) String search,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    Page<WorkOrderListView> result = lists.list(user, from, to, status, machineId, categoryCode, search, page, size);
    return new WorkOrderDtos.WorkOrderPageView(
        result.items().stream().map(WorkOrderController::toListRowDto).toList(),
        result.total(), result.page(), result.size());
  }

  private static WorkOrderDtos.WorkOrderListRowView toListRowDto(WorkOrderListView view) {
    return new WorkOrderDtos.WorkOrderListRowView(view.id(), view.status(), view.categoryCode(), view.categoryLabel(),
        view.machineCode(), view.machineName(), view.plantCode(), view.assignedTechnicianId(),
        view.assignedTechnicianName(), view.description(), view.createdAt(), view.updatedAt(), view.doneReason());
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

  /**
   * Assigns a technician to a workorder (17-1, FR-113 multi-tech). Gate: leadership
   * roles with the same scope rules as {@code /assign}; EXTERNAL and terminal
   * workorders are rejected. The first assignment on an OPEN workorder transitions
   * it to IN_PROGRESS.
   */
  @Operation(operationId = "assignWorkAssignment", summary = "Assign a technician to a workorder (multi-technician)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Assignment created",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkAssignmentView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or malformed JSON"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or assignee not found"),
      @ApiResponse(responseCode = "409", description = "Duplicate assignment, invalid state, or self-assignment")
  })
  @PostMapping("/{id}/assignments")
  public ResponseEntity<WorkOrderDtos.WorkAssignmentView> createAssignment(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @Valid @RequestBody WorkOrderDtos.AssignWorkAssignmentRequest request) {
    var view = workAssignments.assign(user, id, new AssignWorkAssignmentCommand(request.technicianId()));
    return ResponseEntity.status(HttpStatus.CREATED).body(toAssignmentDto(view));
  }

  /**
   * Creates a work log on an active assignment (17-2, blueprint B4, AD-18). Gate:
   * executor (TECHNICIAN/STAFF_MAINTENANCE with an active assignment) OR in-scope
   * leader; EXTERNAL workorders rejected. Backdate is allowed but never before the
   * workorder's created_at. A completed log (end_time + stopped_reason) recomputes MTTR.
   */
  @Operation(operationId = "createWorkLog", summary = "Create a work log on an active assignment")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Work log created",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkLogView.class))),
      @ApiResponse(responseCode = "400", description = "Backdate before workorder creation, or validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden (non-executor, non-leader, or EXTERNAL workorder)"),
      @ApiResponse(responseCode = "404", description = "Workorder or assignment not found"),
      @ApiResponse(responseCode = "409", description = "Assignment is not active on this workorder")
  })
  @PostMapping("/{id}/work-logs")
  public ResponseEntity<WorkOrderDtos.WorkLogView> createWorkLog(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @Valid @RequestBody WorkOrderDtos.CreateWorkLogRequest request) {
    var view = workLogs.create(user, id, new CreateWorkLogCommand(request.workAssignmentId(),
        request.startTime(), request.endTime(), request.stoppedReason(), request.activityNote(),
        request.completionNote(), request.notes()));
    return ResponseEntity.status(HttpStatus.CREATED).body(toWorkLogDto(view));
  }

  /** Updates a work log's mutable fields (gate: log owner executor OR in-scope leader). */
  @Operation(operationId = "updateWorkLog", summary = "Update a work log")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Work log updated",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkLogView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden (not the log owner, or EXTERNAL workorder)"),
      @ApiResponse(responseCode = "404", description = "Workorder or work log not found")
  })
  @PutMapping("/{id}/work-logs/{workLogId}")
  public WorkOrderDtos.WorkLogView updateWorkLog(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @PathVariable UUID workLogId,
      @Valid @RequestBody WorkOrderDtos.UpdateWorkLogRequest request) {
    return toWorkLogDto(workLogs.update(user, id, workLogId, new UpdateWorkLogCommand(
        request.endTime(), request.stoppedReason(), request.completionNote(), request.notes())));
  }

  /** Lists the workorder's work logs ordered by start_time asc (any authenticated user). */
  @Operation(operationId = "listWorkLogs", summary = "List a workorder's work logs")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Work logs returned",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkLogView[].class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @GetMapping("/{id}/work-logs")
  public List<WorkOrderDtos.WorkLogView> listWorkLogs(@PathVariable String id) {
    return workLogs.list(id).stream().map(WorkOrderController::toWorkLogDto).toList();
  }

  // -------------------------------------------------------------------------
  // Work log ratings (17-4, blueprint C2, FR-121)
  // -------------------------------------------------------------------------

  /**
   * Rates a completed work log on a CLOSED workorder (in-scope section leader, FR-121).
   * The rated technician is derived from the work log — never from the request.
   */
  @Operation(operationId = "rateWorkLog", summary = "Rate a completed work log on a closed workorder")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Ratings created",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkLogRatingView.class))),
      @ApiResponse(responseCode = "400", description = "Workorder not closed, work log not completed, or validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or work log not found"),
      @ApiResponse(responseCode = "409", description = "Rating already exists")
  })
  @PostMapping("/{id}/work-logs/{workLogId}/ratings")
  public ResponseEntity<List<WorkOrderDtos.WorkLogRatingView>> rateWorkLog(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @PathVariable UUID workLogId,
      @Valid @RequestBody WorkOrderDtos.RateWorkLogRequest request) {
    var ratings = workLogRatings.rateWorkLog(user, id, workLogId, request.scores());
    return ResponseEntity.status(HttpStatus.CREATED).body(ratings.stream()
        .map(WorkOrderController::toWorkLogRatingDto).toList());
  }

  /** Lists a work log's ratings with their criterion names (any authenticated user). */
  @Operation(operationId = "listWorkLogRatings", summary = "List a work log's ratings")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Ratings returned",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkLogRatingView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder or work log not found")
  })
  @GetMapping("/{id}/work-logs/{workLogId}/ratings")
  public List<WorkOrderDtos.WorkLogRatingView> listWorkLogRatings(@PathVariable String id,
      @PathVariable UUID workLogId) {
    return workLogRatings.listRatings(id, workLogId).stream()
        .map(WorkOrderController::toWorkLogRatingDto).toList();
  }

  // -------------------------------------------------------------------------
  // Work log rating criteria (17-4, blueprint C1, AD-14)
  // -------------------------------------------------------------------------

  /** Creates a work-log rating criterion (SUPER_ADMIN only, audit-logged). */
  @Operation(operationId = "createWorkLogRatingCriterion", summary = "Create a work-log rating criterion (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Criterion created",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkLogRatingCriterionView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed (invalid score range)"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only")
  })
  @PostMapping(value = "/work-log-rating-criteria")
  public ResponseEntity<WorkOrderDtos.WorkLogRatingCriterionView> createWorkLogRatingCriterion(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody WorkOrderDtos.CreateWorkLogRatingCriterionRequest request) {
    var view = workLogRatings.createCriterion(user, new CreateCriterionCommand(request.name().trim(),
        request.description(), request.minScore(), request.maxScore(), request.plantId(),
        request.active() != null ? request.active() : true,
        request.sortOrder() != null ? request.sortOrder() : 0));
    return ResponseEntity.status(HttpStatus.CREATED).body(toWorkLogRatingCriterionDto(view));
  }

  /** Updates a work-log rating criterion's mutable fields (SUPER_ADMIN only). */
  @Operation(operationId = "updateWorkLogRatingCriterion", summary = "Update a work-log rating criterion (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Criterion updated",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkLogRatingCriterionView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed (invalid score range)"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only"),
      @ApiResponse(responseCode = "404", description = "Criterion not found")
  })
  @PutMapping("/work-log-rating-criteria/{criterionId}")
  public WorkOrderDtos.WorkLogRatingCriterionView updateWorkLogRatingCriterion(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID criterionId,
      @Valid @RequestBody WorkOrderDtos.UpdateWorkLogRatingCriterionRequest request) {
    var view = workLogRatings.updateCriterion(user, criterionId, new UpdateCriterionCommand(
        request.name().trim(), request.description(), request.minScore(), request.maxScore(),
        request.plantId(), request.active() != null ? request.active() : true,
        request.sortOrder() != null ? request.sortOrder() : 0));
    return toWorkLogRatingCriterionDto(view);
  }

  /** Deletes a work-log rating criterion (SUPER_ADMIN only; in-use criteria rejected). */
  @Operation(operationId = "deleteWorkLogRatingCriterion", summary = "Delete a work-log rating criterion (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Criterion removed"),
      @ApiResponse(responseCode = "400", description = "Criterion in use by existing ratings"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — SUPER_ADMIN only"),
      @ApiResponse(responseCode = "404", description = "Criterion not found")
  })
  @DeleteMapping("/work-log-rating-criteria/{criterionId}")
  public ResponseEntity<Void> deleteWorkLogRatingCriterion(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID criterionId) {
    workLogRatings.deleteCriterion(user, criterionId);
    return ResponseEntity.noContent().build();
  }

  /** Lists work-log rating criteria ordered by sortOrder (any authenticated user). */
  @Operation(operationId = "listWorkLogRatingCriteria", summary = "List work-log rating criteria ordered by sortOrder")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Criteria returned",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkLogRatingCriterionView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping("/work-log-rating-criteria")
  public List<WorkOrderDtos.WorkLogRatingCriterionView> listWorkLogRatingCriteria() {
    return workLogRatings.listCriteria().stream()
        .map(WorkOrderController::toWorkLogRatingCriterionDto).toList();
  }

  // -------------------------------------------------------------------------
  // Workorder quality ratings (17-5, blueprint C4-C6, FR-124)
  // -------------------------------------------------------------------------

  /**
   * Submits a workorder quality rating (FR-124). The PRODUCTION_LEADER of the affected
   * line rates a CLOSED maintenance workorder; one rating per workorder, immutable after
   * submission.
   */
  @Operation(operationId = "submitWorkOrderQualityRating", summary = "Submit a workorder quality rating (PRODUCTION_LEADER)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Rating submitted",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.QualityRatingView.class))),
      @ApiResponse(responseCode = "400", description = "Workorder not closed or validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden — PRODUCTION_LEADER with plant access only"),
      @ApiResponse(responseCode = "404", description = "Workorder or machine not found"),
      @ApiResponse(responseCode = "409", description = "Rating already exists")
  })
  @PostMapping("/{id}/quality-rating")
  public ResponseEntity<WorkOrderDtos.QualityRatingView> submitQualityRating(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @Valid @RequestBody WorkOrderDtos.SubmitQualityRatingRequest request) {
    var view = qualityRatings.submit(user, id,
        new SubmitQualityRatingCommand(request.technicianIds(), request.scores(),
            request.cleanlinessScore(), request.tidinessScore(), request.speedScore()));
    return ResponseEntity.status(HttpStatus.CREATED).body(toQualityRatingDto(view));
  }

  /** Gets the current workorder quality rating, expiring PENDING ones past due (any authenticated user). */
  @Operation(operationId = "getWorkOrderQualityRating", summary = "Get a workorder's quality rating (expires past-due PENDING)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Rating returned",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.QualityRatingView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder or rating not found")
  })
  @GetMapping("/{id}/quality-rating")
  public WorkOrderDtos.QualityRatingView getQualityRating(@PathVariable String id) {
    return toQualityRatingDto(qualityRatings.get(id));
  }

  /** Lists workorder rating criteria ordered by sortOrder (any authenticated user). */
  @Operation(operationId = "listWorkOrderRatingCriteria", summary = "List workorder quality rating criteria ordered by sortOrder")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Criteria returned",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkOrderRatingCriterionView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping("/quality-rating-criteria")
  public List<WorkOrderDtos.WorkOrderRatingCriterionView> listWorkOrderRatingCriteria() {
    return qualityRatings.listCriteria().stream()
        .map(WorkOrderController::toWorkOrderRatingCriterionDto).toList();
  }

  /** Drops an active assignment (soft-deactivate; 17-1, AD-17). */
  @Operation(operationId = "dropWorkAssignment", summary = "Drop an active work assignment")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Assignment dropped",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkAssignmentView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or assignment not found"),
      @ApiResponse(responseCode = "409", description = "Assignment already dropped")
  })
  @PostMapping("/{id}/assignments/{assignmentId}/drop")
  public WorkOrderDtos.WorkAssignmentView dropAssignment(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @PathVariable UUID assignmentId) {
    return toAssignmentDto(workAssignments.drop(user, id, assignmentId));
  }

  /** Lists the workorder's assignments ordered by assignedAt asc (any authenticated user). */
  @Operation(operationId = "listWorkAssignments", summary = "List a workorder's assignments")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Assignments returned",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkAssignmentView[].class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @GetMapping("/{id}/assignments")
  public List<WorkOrderDtos.WorkAssignmentView> listAssignments(@PathVariable String id) {
    return workAssignments.list(id).stream().map(WorkOrderController::toAssignmentDto).toList();
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

  @Operation(operationId = "startRepairSession", summary = "Start a repair session on an IN_PROGRESS workorder")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Session started", content = @Content(schema = @Schema(implementation = RepairSessionsView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or malformed JSON"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "409", description = "Session already open, overlap, or workorder not in progress")
  })
  @PostMapping("/{id}/sessions")
  public RepairSessionsView startSession(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @Valid @RequestBody(required = false) StartSessionRequest request) {
    var command = new StartSessionCommand(request != null ? request.description() : null);
    return toSessionsDto(workOrders.startSession(user, id, command));
  }

  @Operation(operationId = "stopRepairSession", summary = "Stop the open repair session and recompute MTTR")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Session stopped", content = @Content(schema = @Schema(implementation = RepairSessionsView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "409", description = "No open session")
  })
  @PostMapping("/{id}/sessions/stop")
  public RepairSessionsView stopSession(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
    return toSessionsDto(workOrders.stopSession(user, id));
  }

  @Operation(operationId = "listRepairSessions", summary = "List the workorder's repair sessions")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Sessions returned", content = @Content(schema = @Schema(implementation = RepairSessionsView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @GetMapping("/{id}/sessions")
  public RepairSessionsView listSessions(@PathVariable String id) {
    return toSessionsDto(workOrders.listSessions(id));
  }

  /**
   * Multipart parts mirror the API contract names: {@code filename} and {@code contentType}
   * are text parts, {@code data} carries the file bytes — same pattern as 8-4 sparepart images.
   */
  @Operation(operationId = "createWorkorderAttachment", summary = "Upload evidence or a technical drawing to a workorder")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Attachment stored; presigned URL returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = WorkorderAttachmentView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or multipart error"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed")
  })
  @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public WorkorderAttachmentView createAttachment(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @RequestParam("filename") @Size(max = 255) String filename,
      @RequestParam("contentType") @Size(max = 100) String contentType,
      @RequestPart("data") MultipartFile data) throws IOException {
    return toAttachmentDto(evidence.create(user, id, command(filename, contentType, data)));
  }

  @Operation(operationId = "replaceWorkorderAttachment", summary = "Replace an attachment's file (deletes the old object)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Attachment replaced; presigned URL returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = WorkorderAttachmentView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or multipart error"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or attachment not found"),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed")
  })
  @PutMapping(value = "/{id}/attachments/{attachmentId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public WorkorderAttachmentView replaceAttachment(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @PathVariable UUID attachmentId,
      @RequestParam("filename") @Size(max = 255) String filename,
      @RequestParam("contentType") @Size(max = 100) String contentType,
      @RequestPart("data") MultipartFile data) throws IOException {
    return toAttachmentDto(evidence.replace(user, id, attachmentId, command(filename, contentType, data)));
  }

  @Operation(operationId = "listWorkorderAttachments", summary = "List a workorder's attachments ordered by createdAt")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Attachments returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = WorkorderAttachmentsView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @GetMapping("/{id}/attachments")
  public WorkorderAttachmentsView listAttachments(@PathVariable String id) {
    return toAttachmentsDto(evidence.list(id));
  }

  @Operation(operationId = "getWorkorderAttachment", summary = "Get a single attachment with its presigned URL")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Attachment returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = WorkorderAttachmentView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder or attachment not found")
  })
  @GetMapping("/{id}/attachments/{attachmentId}")
  public WorkorderAttachmentView getAttachment(@PathVariable String id, @PathVariable UUID attachmentId) {
    return toAttachmentDto(evidence.get(id, attachmentId));
  }

  @Operation(operationId = "deleteWorkorderAttachment", summary = "Delete an attachment (Garage object and row)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Attachment removed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or attachment not found"),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed")
  })
  @DeleteMapping("/{id}/attachments/{attachmentId}")
  public ResponseEntity<Void> deleteAttachment(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @PathVariable UUID attachmentId) {
    evidence.delete(user, id, attachmentId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Report write (FR-117/FR-118/FR-122): four-section narrative + optional CP/CPK
   * values, FMEA failure tag and stop-time reason/detail. Gate: executor/leader.
   */
  @Operation(operationId = "saveWorkOrderReport", summary = "Save the workorder report narrative and capability data")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Report saved",
          content = @Content(schema = @Schema(implementation = WorkOrderReportView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed (narrative length, CPK range, enum)"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @PutMapping("/{id}/report")
  public WorkOrderReportView saveReport(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @Valid @RequestBody SaveWorkOrderReportRequest request) {
    var command = new SaveReportCommand(request.reportChronological(), request.reportAnalyze(),
        request.reportCorrective(), request.reportPreventive(), request.cpCkLower(), request.cpCkUpper(),
        request.cpk(), request.fmeaFailureType(), request.stopTimeReason(), request.stopTimeDetail());
    return toReportDto(report.saveReport(user, id, command));
  }

  /** Report read (GET /{id}/report): any authenticated user; fresh short-TTL presigned URL. */
  @Operation(operationId = "getWorkOrderReport", summary = "Read the workorder report and capability data")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Report returned",
          content = @Content(schema = @Schema(implementation = WorkOrderReportView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @GetMapping("/{id}/report")
  public WorkOrderReportView getReport(@PathVariable String id) {
    return toReportDto(report.getReport(id));
  }

  /**
   * CP/CPK capability PDF upload (PUT /{id}/report/cpk). PDF-only, size-capped by
   * {@code syncro.workorder.evidence.max-bytes}. Multipart parts mirror the evidence
   * contract: {@code filename}/{@code contentType} text parts, {@code data} file bytes.
   */
  @Operation(operationId = "uploadWorkOrderCpkPdf", summary = "Upload or replace the CP/CPK capability PDF")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "CP/CPK PDF stored; presigned URL returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = WorkOrderReportView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or multipart error (PDF-only, size cap)"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed")
  })
  @PutMapping(value = "/{id}/report/cpk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public WorkOrderReportView uploadCpkPdf(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @RequestParam("filename") @Size(max = 255) String filename,
      @RequestParam("contentType") @Size(max = 100) String contentType,
      @RequestPart("data") MultipartFile data) throws IOException {
    return toReportDto(report.uploadCpkPdf(user, id, new CpkPdfCommand(filename, contentType, data.getBytes())));
  }

  /** CP/CPK PDF delete (DELETE /{id}/report/cpk): removes object + clears key, idempotent. */
  @Operation(operationId = "deleteWorkOrderCpkPdf", summary = "Delete the CP/CPK capability PDF")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "CP/CPK PDF removed",
          content = @Content(schema = @Schema(implementation = WorkOrderReportView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed")
  })
  @DeleteMapping("/{id}/report/cpk")
  public WorkOrderReportView deleteCpkPdf(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
    return toReportDto(report.deleteCpkPdf(user, id));
  }

  // -------------------------------------------------------------------------
  // Todos & kanban (10.7)
  // -------------------------------------------------------------------------

  /** Creates a todo on a workorder (gate: executor/leader; non-terminal workorder). */
  @Operation(operationId = "createWorkorderTodo", summary = "Create a todo on a workorder")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Todo created",
          content = @Content(schema = @Schema(implementation = TodoView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed or workorder is terminal"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @PostMapping("/{id}/todos")
  public ResponseEntity<TodoView> createTodo(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @Valid @RequestBody CreateTodoRequest request) {
    var todo = toTodoDto(todos.create(user, id,
        new CreateTodoCommand(request.title(), request.description(), request.assignedTechnicianId())));
    return ResponseEntity.status(HttpStatus.CREATED).body(todo);
  }

  /** Lists the workorder's todos ordered by sortOrder (any authenticated user). */
  @Operation(operationId = "listWorkorderTodos", summary = "List a workorder's todos")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Todos returned",
          content = @Content(schema = @Schema(implementation = TodoView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @GetMapping("/{id}/todos")
  public List<TodoView> listTodos(@PathVariable String id) {
    return todos.list(id).stream().map(WorkOrderController::toTodoDto).toList();
  }

  /** Assigns (or changes) a todo's technician (gate: executor/leader; non-terminal). */
  @Operation(operationId = "assignWorkorderTodo", summary = "Assign a todo to a technician")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Todo assigned",
          content = @Content(schema = @Schema(implementation = TodoView.class))),
      @ApiResponse(responseCode = "400", description = "Workorder is terminal"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or todo not found")
  })
  @PutMapping("/{id}/todos/{todoId}/assign")
  public TodoView assignTodo(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @PathVariable UUID todoId, @Valid @RequestBody AssignTodoRequest request) {
    return toTodoDto(todos.assign(user, id, todoId, request.assignedTechnicianId()));
  }

  /** Marks a todo complete (gate: executor/leader OR assigned technician; non-terminal). */
  @Operation(operationId = "completeWorkorderTodo", summary = "Mark a todo complete")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Todo completed",
          content = @Content(schema = @Schema(implementation = TodoView.class))),
      @ApiResponse(responseCode = "400", description = "Workorder is terminal or todo already completed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or todo not found")
  })
  @PutMapping("/{id}/todos/{todoId}/complete")
  public TodoView completeTodo(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @PathVariable UUID todoId) {
    return toTodoDto(todos.complete(user, id, todoId));
  }

  /** Reorders a todo within its workorder (gate: executor/leader; non-terminal). */
  @Operation(operationId = "reorderWorkorderTodo", summary = "Reorder a todo within its workorder")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Todo reordered",
          content = @Content(schema = @Schema(implementation = TodoView.class))),
      @ApiResponse(responseCode = "400", description = "Workorder is terminal"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or todo not found")
  })
  @PutMapping("/{id}/todos/{todoId}/reorder")
  public TodoView reorderTodo(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @PathVariable UUID todoId, @Valid @RequestBody ReorderTodoRequest request) {
    return toTodoDto(todos.reorder(user, id, todoId, request.sortOrder()));
  }

  /** Deletes a todo (gate: executor/leader; non-terminal). */
  @Operation(operationId = "deleteWorkorderTodo", summary = "Delete a todo")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Todo removed"),
      @ApiResponse(responseCode = "400", description = "Workorder is terminal"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or todo not found")
  })
  @DeleteMapping("/{id}/todos/{todoId}")
  public ResponseEntity<Void> deleteTodo(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @PathVariable UUID todoId) {
    todos.delete(user, id, todoId);
    return ResponseEntity.noContent().build();
  }

  /** Kanban board (GET /kanban): scope-filtered workorders grouped by status with todos embedded. */
  @Operation(operationId = "getWorkorderKanban", summary = "Kanban board grouped by workorder status")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Kanban view returned",
          content = @Content(schema = @Schema(implementation = KanbanView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping("/kanban")
  public KanbanView kanban(@AuthenticationPrincipal AuthenticatedUser user) {
    var view = todos.kanban(user);
    return new KanbanView(view.groups().entrySet().stream()
        .collect(java.util.stream.Collectors.toMap(
            java.util.Map.Entry::getKey,
            entry -> entry.getValue().stream().map(WorkOrderController::toKanbanItemDto).toList())));
  }

  // -------------------------------------------------------------------------
  // Ratings (10.8, FR-121/FR-124)
  // -------------------------------------------------------------------------

  /** Rates an executing technician on a CLOSED workorder (in-scope section leader, FR-121). */
  @Operation(operationId = "rateWorkorderTechnician", summary = "Rate a technician who executed a closed workorder")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Rating created",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.RatingView.class))),
      @ApiResponse(responseCode = "400", description = "Workorder not closed, validation failed, or rated user not an executor"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or rated user not found"),
      @ApiResponse(responseCode = "409", description = "Rating already exists")
  })
  @PostMapping("/{id}/ratings/technician")
  public ResponseEntity<WorkOrderDtos.RatingView> rateTechnician(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @Valid @RequestBody WorkOrderDtos.RateTechnicianRequest request) {
    var rating = ratings.rateTechnician(user, id, request.ratedUserId(), request.scores());
    return ResponseEntity.status(HttpStatus.CREATED).body(toRatingDto(rating));
  }

  /** Rates a CLOSED workorder itself (PRODUCTION_LEADER with plant access, FR-124). */
  @Operation(operationId = "rateWorkorder", summary = "Rate a closed maintenance workorder")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Rating created",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.RatingView.class))),
      @ApiResponse(responseCode = "400", description = "Workorder not closed or validation failed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "409", description = "Rating already exists")
  })
  @PostMapping("/{id}/ratings/workorder")
  public ResponseEntity<WorkOrderDtos.RatingView> rateWorkorder(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @Valid @RequestBody WorkOrderDtos.RateWorkorderRequest request) {
    var rating = ratings.rateWorkorder(user, id, request.scores());
    return ResponseEntity.status(HttpStatus.CREATED).body(toRatingDto(rating));
  }

  /** Lists a workorder's ratings with their per-dimension scores (any authenticated user). */
  @Operation(operationId = "listWorkorderRatings", summary = "List a workorder's ratings")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Ratings returned",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.RatingView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @GetMapping("/{id}/ratings")
  public List<WorkOrderDtos.RatingView> listRatings(@PathVariable String id) {
    return ratings.listRatings(id).stream().map(WorkOrderController::toRatingDto).toList();
  }

  /** Ratings page (GET /ratings): CLOSED workorders the user can rate (FR-121/FR-124). */
  @Operation(operationId = "listRateableWorkorders", summary = "CLOSED workorders the user can rate")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Rateable workorders returned",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.RateableWorkorderView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping("/ratings")
  public List<WorkOrderDtos.RateableWorkorderView> listRateable(@AuthenticationPrincipal AuthenticatedUser user) {
    return ratings.listRateableClosed(user).stream().map(WorkOrderController::toRateableDto).toList();
  }

  // -------------------------------------------------------------------------
  // 4-hour acknowledgment (14-4, FR-181)
  // -------------------------------------------------------------------------

  /**
   * Records the leader's ack for a workorder (POST /{id}/acknowledge). Stops further
   * escalation (cancels pending jobs) and writes an audit row. Any authenticated user.
   */
  @Operation(operationId = "acknowledgeWorkorder", summary = "Acknowledge a workorder's 4-hour escalation")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Workorder acknowledged",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.AckView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "409", description = "Workorder already acknowledged")
  })
  @PostMapping("/{id}/acknowledge")
  public WorkOrderDtos.AckView acknowledge(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, HttpServletRequest request) {
    var traceId = (String) request.getAttribute(JwtAuthenticationFilter.AUTO_LOGIN_ATTR);
    AckResult result = acks.acknowledge(user, id, traceId);
    return new WorkOrderDtos.AckView(result.workOrderId(), UUID.fromString(user.id()), result.acknowledgedAt());
  }

  /**
   * The 4-hour ack landing task list (GET /ack-task-list): acknowledged vs pending acks
   * and rated vs unrated closed workorders. Accepts an AUTO_LOGIN token via the filter
   * (FR-181) — phone-bound, single-use, short-lived.
   */
  @Operation(operationId = "getWorkorderAckTaskList", summary = "4-hour ack task list (acknowledged vs pending, rated vs unrated)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Task list returned",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.AckTaskListView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping("/ack-task-list")
  public WorkOrderDtos.AckTaskListView ackTaskList(@AuthenticationPrincipal AuthenticatedUser user) {
    TaskListView view = acks.taskList(user);
    return new WorkOrderDtos.AckTaskListView(
        view.acknowledged().stream().map(WorkOrderController::toAckEntryDto).toList(),
        view.pending().stream().map(WorkOrderController::toAckEntryDto).toList(),
        view.rated().stream().map(WorkOrderController::toClosedEntryDto).toList(),
        view.unrated().stream().map(WorkOrderController::toClosedEntryDto).toList());
  }

  private static WorkOrderDtos.AckEntryView toAckEntryDto(WorkOrderAckService.AckEntry entry) {
    return new WorkOrderDtos.AckEntryView(entry.workOrderId(), entry.acknowledgedBy(), entry.acknowledgedAt());
  }

  private static WorkOrderDtos.ClosedWorkorderEntryView toClosedEntryDto(
      WorkOrderAckService.ClosedWorkorderEntry entry) {
    return new WorkOrderDtos.ClosedWorkorderEntryView(entry.id(), entry.status(), entry.description(), entry.rated());
  }

  // -------------------------------------------------------------------------
  // Print report & signature (14-3, FR-175)
  // -------------------------------------------------------------------------

  /**
   * Aggregate print report (GET /{id}/print-report): WO header, sessions, narrative,
   * CP/CPK, evidence, sparepart requests, and the signature block (when present) for the
   * WYSIWYG browser-print page. Any authenticated user.
   */
  @Operation(operationId = "getWorkorderPrintReport", summary = "Aggregate workorder print report (WYSIWYG browser print)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Print report returned",
          content = @Content(schema = @Schema(implementation = WorkorderPrintReportView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @GetMapping("/{id}/print-report")
  public WorkorderPrintReportView printReport(@PathVariable String id) {
    return printReports.get(id);
  }

  /**
   * Workorder approval with signature (POST /{id}/approve). Only DONE/CLOSED workorders
   * accept signatures; gate: in-scope leader/SPV. Duplicate approve → 409.
   */
  @Operation(operationId = "approveWorkorder", summary = "Approve a DONE/CLOSED workorder with a signature")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Workorder approved; signature recorded",
          content = @Content(schema = @Schema(implementation = WorkOrderDtos.WorkorderSignatureView.class))),
      @ApiResponse(responseCode = "400", description = "Workorder not DONE/CLOSED"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden (leader/SPV only)"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "409", description = "Workorder already signed")
  })
  @PostMapping("/{id}/approve")
  public WorkOrderDtos.WorkorderSignatureView approve(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @Valid @RequestBody WorkOrderDtos.ApproveWorkorderRequest request) {
    var result = signatures.approve(user, id,
        new ApproveSignatureCommand(request.signatureObjectKey(), request.signerIdentity()));
    return toSignatureDto(result);
  }

  private static WorkOrderDtos.RatingView toRatingDto(WorkorderRating rating) {
    return new WorkOrderDtos.RatingView(rating.id(), rating.workorderId(), rating.ratingType().name(),
        rating.ratedUserId(), rating.raterUserId(), rating.createdAt(),
        rating.scores().entrySet().stream()
            .map(entry -> new WorkOrderDtos.RatingScoreView(entry.getKey(), entry.getValue()))
            .toList());
  }

  private static WorkOrderDtos.RateableWorkorderView toRateableDto(WorkOrderRatingService.RateableWorkorder item) {
    return new WorkOrderDtos.RateableWorkorderView(item.id(), item.source(), item.status(), item.categoryCode(),
        item.machineId(), item.description(), item.assignedTechnicianId(), item.createdAt(), item.executorPool());
  }

  private static WorkOrderDtos.WorkorderSignatureView toSignatureDto(SignatureResult result) {
    return new WorkOrderDtos.WorkorderSignatureView(result.id(), result.signatureObjectKey(),
        result.signerIdentity(), result.signedBy(), result.signedAt());
  }

  private static WorkOrderDtos.WorkLogView toWorkLogDto(WorkLogService.WorkLogView view) {
    return new WorkOrderDtos.WorkLogView(view.id(), view.workAssignmentId(), view.workOrderId(),
        view.technicianId(), view.startTime(), view.endTime(), view.stoppedReason(),
        view.activityNote(), view.completionNote(), view.notes(), view.createdAt(), view.updatedAt());
  }

  private static WorkOrderDtos.WorkLogRatingView toWorkLogRatingDto(WorkLogRatingService.WorkLogRatingView view) {
    return new WorkOrderDtos.WorkLogRatingView(view.id(), view.workLogId(), view.workOrderId(),
        view.criterionId(), view.criterionName(), view.score(), view.ratedBy(), view.ratedAt());
  }

  private static WorkOrderDtos.WorkLogRatingCriterionView toWorkLogRatingCriterionDto(
      WorkLogRatingService.WorkLogRatingCriterionView view) {
    return new WorkOrderDtos.WorkLogRatingCriterionView(view.id(), view.name(), view.description(),
        view.minScore(), view.maxScore(), view.plantId(), view.active(), view.sortOrder(),
        view.createdAt(), view.updatedAt());
  }

  private static WorkOrderDtos.QualityRatingView toQualityRatingDto(
      WorkOrderQualityRatingService.QualityRatingView view) {
    var scoreViews = view.scores().stream()
        .map(s -> new WorkOrderDtos.QualityRatingScoreView(s.criterionId(), s.criterionName(), s.score()))
        .toList();
    return new WorkOrderDtos.QualityRatingView(view.id(), view.workOrderId(), view.status(),
        view.cleanlinessScore(), view.tidinessScore(), view.speedScore(), view.dueAt(),
        view.submittedAt(), view.submittedBy(), view.remarks(), scoreViews, view.technicianIds());
  }

  private static WorkOrderDtos.WorkOrderRatingCriterionView toWorkOrderRatingCriterionDto(
      WorkOrderQualityRatingService.WorkOrderRatingCriterionView view) {
    return new WorkOrderDtos.WorkOrderRatingCriterionView(view.id(), view.name(), view.description(),
        view.minScore(), view.maxScore(), view.plantId(), view.active(), view.sortOrder());
  }

  private static WorkOrderDtos.WorkAssignmentView toAssignmentDto(WorkAssignmentView view) {
    return new WorkOrderDtos.WorkAssignmentView(view.id(), view.workOrderId(), view.technicianId(),
        view.assignedBy(), view.assignedAt(), view.droppedAt(), view.droppedBy(), view.isActive());
  }

  private static TodoView toTodoDto(WorkOrderTodo todo) {
    return new TodoView(todo.id(), todo.workorderId(), todo.title(), todo.description(),
        todo.assignedTechnicianId(), todo.status(), todo.sortOrder(), todo.createdBy(), todo.createdAt(),
        todo.updatedAt(), todo.completedAt());
  }

  private static WorkOrderDtos.WorkOrderKanbanItem toKanbanItemDto(
      WorkOrderTodoService.WorkOrderKanbanItem item) {
    return new WorkOrderDtos.WorkOrderKanbanItem(item.id(), item.status(), item.categoryCode(), item.machineId(),
        item.description(), item.assignedTechnicianId(), item.createdAt(),
        item.todos().stream().map(WorkOrderController::toTodoDto).toList());
  }

  private static WorkOrderReportView toReportDto(WorkOrderReportService.WorkOrderReportView view) {
    return new WorkOrderReportView(view.workOrderId(), view.reportChronological(), view.reportAnalyze(),
        view.reportCorrective(), view.reportPreventive(), view.cpCkLower(), view.cpCkUpper(), view.cpk(),
        view.cpkPdfPresignedUrl(), view.fmeaFailureType(), view.stopTimeReason(), view.stopTimeDetail());
  }

  private static EvidenceCommand command(String filename, String contentType, MultipartFile data)
      throws IOException {
    return new EvidenceCommand(filename, contentType, data.getBytes());
  }

  private WorkorderAttachmentsView toAttachmentsDto(WorkOrderEvidenceService.WorkorderAttachmentsView view) {
    return new WorkorderAttachmentsView(view.workOrderId(),
        view.attachments().stream().map(WorkOrderController::toAttachmentDto).toList());
  }

  private static WorkorderAttachmentView toAttachmentDto(WorkOrderEvidenceService.WorkorderAttachmentView view) {
    return new WorkorderAttachmentView(view.id(), view.workOrderId(), view.filename(), view.contentType(),
        view.objectKey(), view.sizeBytes(), view.uploadedBy(), view.createdAt(), view.updatedAt(),
        view.presignedUrl());
  }

  private WorkOrderView toDto(WorkOrder workOrder) {
    return new WorkOrderView(workOrder.id(), workOrder.source(), workOrder.status(), workOrder.categoryId(),
        workOrder.machineId(), workOrder.description(), workOrder.parentId(), workOrder.assignedTechnicianId(),
        workOrder.createdBy(), workOrder.createdAt(), workOrder.updatedAt(), workOrder.mttrMinutes(),
        workOrder.responseTimeMinutes(), workOrder.doneReason());
  }

  private RepairSessionsView toSessionsDto(RepairSessionsResult result) {
    return new RepairSessionsView(toDto(result.workOrder()),
        result.sessions().stream().map(WorkOrderController::toSessionDto).toList());
  }

  private static RepairSessionView toSessionDto(RepairSession session) {
    return new RepairSessionView(session.id(), session.workOrderId(), session.technicianId(), session.description(),
        session.startedAt(), session.endedAt(), session.durationMinutes());
  }
}