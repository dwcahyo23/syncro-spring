package com.syncro.maintenance.api;

import com.syncro.maintenance.domain.workorder.FmeaFailureType;
import com.syncro.maintenance.domain.workorder.StopTimeReason;
import com.syncro.maintenance.domain.workorder.TodoStatus;
import com.syncro.maintenance.domain.workorder.WorkLogStoppedReason;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.domain.workorder.WorkRatingStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkOrderDtos {
  private WorkOrderDtos() {
  }

  /** Category codes are stored uppercase and must be safe for OPA glob matching. */
  private static final String CODE_PATTERN = "^[A-Za-z0-9._-]{1,16}$";

  public record CreateWorkOrderRequest(
      @NotBlank @Pattern(regexp = CODE_PATTERN) String categoryCode,
      @NotNull UUID machineId,
      @Size(max = 4000) String description,
      @Size(max = 50) String parentId) {
  }

  public record AssignWorkOrderRequest(@NotNull UUID assigneeUserId) {
  }

  public record TransitionWorkOrderRequest(@NotNull WorkOrderStatus toStatus, @Size(max = 1000) String reason,
      @Size(max = 2000) String overrideReason) {
  }

  public record WorkOrderView(String id, String source, WorkOrderStatus status, UUID categoryId, UUID machineId,
      String description, String parentId, UUID assignedTechnicianId, UUID createdBy, Instant createdAt,
      Instant updatedAt, Long mttrMinutes, Long responseTimeMinutes, String doneReason) {
  }

  public record StartSessionRequest(@Size(max = 2000) String description) {
  }

  public record RepairSessionView(UUID id, String workOrderId, UUID technicianId, String description,
      Instant startedAt, Instant endedAt, Long durationMinutes) {
  }

  public record RepairSessionsView(WorkOrderView workOrder, List<RepairSessionView> sessions) {
  }

  public record WorkorderAttachmentView(UUID id, String workOrderId, String filename, String contentType,
      String objectKey, long sizeBytes, UUID uploadedBy, Instant createdAt, Instant updatedAt,
      String presignedUrl) {
  }

  public record WorkorderAttachmentsView(String workOrderId, List<WorkorderAttachmentView> attachments) {
  }

  /**
   * Report save body (story 10-6). Narratives are trimmed/normalized in the service
   * (blank → null); {@code @DecimalMin} guards negative capability indices at the DTO
   * boundary, the service re-validates for NUMERIC(8,4) safety. Enum fields reject
   * unknown values via Jackson → 400 VALIDATION_ERROR (same as toStatus).
   */
  public record SaveWorkOrderReportRequest(
      @Size(max = 4000) String reportChronological,
      @Size(max = 4000) String reportAnalyze,
      @Size(max = 4000) String reportCorrective,
      @Size(max = 4000) String reportPreventive,
      @DecimalMin("0") BigDecimal cpCkLower,
      @DecimalMin("0") BigDecimal cpCkUpper,
      @DecimalMin("0") BigDecimal cpk,
      FmeaFailureType fmeaFailureType,
      StopTimeReason stopTimeReason,
      @Size(max = 500) String stopTimeDetail) {
  }

  public record WorkOrderReportView(String workOrderId, String reportChronological, String reportAnalyze,
      String reportCorrective, String reportPreventive, BigDecimal cpCkLower, BigDecimal cpCkUpper, BigDecimal cpk,
      String cpkPdfPresignedUrl, String fmeaFailureType, String stopTimeReason, String stopTimeDetail) {
  }

  /**
   * Todo create body (story 10-7). The title is {@code @NotBlank}/{@code @Size} guarded at
   * the DTO boundary; the service re-validates after trimming so padding cannot defeat the
   * 200-char column. {@code assignedTechnicianId} is optional — a todo may start unassigned.
   */
  public record CreateTodoRequest(
      @NotBlank @Size(max = 200) String title,
      @Size(max = 4000) String description,
      UUID assignedTechnicianId) {
  }

  public record AssignTodoRequest(@NotNull UUID assignedTechnicianId) {
  }

  public record ReorderTodoRequest(@NotNull @Min(0) @Max(1_000_000) Integer sortOrder) {
  }

  public record TodoView(UUID id, String workorderId, String title, String description,
      UUID assignedTechnicianId, TodoStatus status, int sortOrder, UUID createdBy, Instant createdAt,
      Instant updatedAt, Instant completedAt) {
  }

  /** One kanban board item: a workorder with its embedded todos (FR-119). */
  public record WorkOrderKanbanItem(String id, WorkOrderStatus status, String categoryCode, UUID machineId,
      String description, UUID assignedTechnicianId, Instant createdAt, List<TodoView> todos) {
  }

  /** Kanban view: status → workorder items; every non-terminal status key is always present. */
  public record KanbanView(Map<WorkOrderStatus, List<WorkOrderKanbanItem>> groups) {
  }

  // -------------------------------------------------------------------------
  // Workorder list table (workorder-table story)
  // -------------------------------------------------------------------------

  /**
   * One list row (workorder-table story). The API exposes {@code id} (WO-YYMMXXXX /
   * sheet_no), machine {@code code · name}, plant {@code code}, category code/label and
   * the resolved technician name. Raw UUIDs ({@code assignedTechnicianId}) exist for
   * keys/actions but are never rendered by the UI (spec: no raw UUIDs leaked in cells).
   */
  public record WorkOrderListRowView(String id, WorkOrderStatus status, String categoryCode, String categoryLabel,
      String machineCode, String machineName, String plantCode, UUID assignedTechnicianId,
      String assignedTechnicianName, String description, Instant createdAt, Instant updatedAt, String doneReason) {
  }

  /** Server-paginated list envelope: current page items + matching count + page/size echo. */
  public record WorkOrderPageView(List<WorkOrderListRowView> items, long total, int page, int size) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }

  // -------------------------------------------------------------------------
  // Ratings (10.8, FR-121/FR-124)
  // -------------------------------------------------------------------------

  /**
   * Technician rating body (FR-121). {@code scores} maps dimension code → 1..5; the
   * service validates codes and ranges (missing dimensions allowed). Unknown codes and
   * out-of-range values → 400 VALIDATION_ERROR.
   */
  public record RateTechnicianRequest(
      @NotNull UUID ratedUserId,
      @NotNull Map<String, Integer> scores) {
  }

  /** Workorder rating body (FR-124): scores only — bound to the workorder, no rated user. */
  public record RateWorkorderRequest(@NotNull Map<String, Integer> scores) {
  }

  public record RatingScoreView(String dimensionCode, int score) {
  }

  /** Read model for a submitted rating with its per-dimension scores. */
  public record RatingView(UUID id, String workorderId, String ratingType, UUID ratedUserId, UUID raterUserId,
      Instant createdAt, List<RatingScoreView> scores) {
  }

  public record CreateRatingDimensionRequest(
      @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Z0-9_]{1,40}$") String code,
      @NotBlank @Size(max = 100) String label,
      @Min(0) @Max(100_000) Integer sortOrder) {
  }

  public record UpdateRatingDimensionRequest(
      @NotBlank @Size(max = 100) String label,
      @NotNull @Min(0) @Max(100_000) Integer sortOrder) {
  }

  /** One rateable CLOSED workorder on the ratings page (FR-121/FR-124 list surface). */
  public record RateableWorkorderView(String id, String source, WorkOrderStatus status, String categoryCode,
      UUID machineId, String description, UUID assignedTechnicianId, Instant createdAt, List<UUID> executorPool) {
  }

  // -------------------------------------------------------------------------
  // Print report & signature (14-3, FR-175)
  // -------------------------------------------------------------------------

  /**
   * Approve body (POST /{id}/approve). {@code signatureObjectKey} is the Garage object
   * key of the already-uploaded signature image; {@code signerIdentity} defaults to the
   * approver's login identifier when blank.
   */
  public record ApproveWorkorderRequest(
      @NotBlank @Size(max = 512) String signatureObjectKey,
      @Size(max = 200) String signerIdentity) {
  }

  /** Signature view returned after a successful approve. */
  public record WorkorderSignatureView(UUID id, String signatureObjectKey, String signerIdentity,
      UUID signedBy, Instant signedAt) {
  }

  // -------------------------------------------------------------------------
  // 4-hour acknowledgment (14-4, FR-181)
  // -------------------------------------------------------------------------

  /** Ack view returned after a successful acknowledge. */
  public record AckView(String workOrderId, UUID acknowledgedBy, Instant acknowledgedAt) {
  }

  /** One ack entry on the task list. */
  public record AckEntryView(String workOrderId, UUID acknowledgedBy, Instant acknowledgedAt) {
  }

  /** One closed-workorder entry on the task list (rated flag splits rated vs unrated). */
  public record ClosedWorkorderEntryView(String id, WorkOrderStatus status, String description, boolean rated) {
  }

  // -------------------------------------------------------------------------
  // Work assignments (17-1, blueprint B3, AD-17)
  // -------------------------------------------------------------------------

  /** Assign a technician to a workorder (POST /{id}/assignments). */
  public record AssignWorkAssignmentRequest(@NotNull UUID technicianId) {
  }

  /** Read model for a single work-assignment row. */
  public record WorkAssignmentView(
      UUID id,
      String workOrderId,
      UUID technicianId,
      UUID assignedBy,
      Instant assignedAt,
      Instant droppedAt,
      UUID droppedBy,
      boolean isActive) {
  }

  // -------------------------------------------------------------------------
  // Work logs (17-2, blueprint B4, AD-18)
  // -------------------------------------------------------------------------

  /**
   * Create a work log on an active assignment (POST /{id}/work-logs). The technician
   * is derived from the assignment — never taken from the request. {@code activityNote}
   * is mandatory (the DB CHECK is the backstop); backdate is allowed but never before
   * the workorder's {@code created_at} (service-enforced, AD-18).
   */
  public record CreateWorkLogRequest(
      @NotNull UUID workAssignmentId,
      @NotNull Instant startTime,
      Instant endTime,
      WorkLogStoppedReason stoppedReason,
      @NotBlank @Size(max = 2000) String activityNote,
      String completionNote,
      String notes) {
  }

  /** Update a work log's mutable fields (PUT /{id}/work-logs/{workLogId}). */
  public record UpdateWorkLogRequest(
      Instant endTime,
      WorkLogStoppedReason stoppedReason,
      String completionNote,
      String notes) {
  }

  /** Read model for a single work-log row. */
  public record WorkLogView(
      UUID id,
      UUID workAssignmentId,
      String workOrderId,
      UUID technicianId,
      Instant startTime,
      Instant endTime,
      WorkLogStoppedReason stoppedReason,
      String activityNote,
      String completionNote,
      String notes,
      Instant createdAt,
      Instant updatedAt) {
  }

  /** The 4-hour ack landing task list. */
  public record AckTaskListView(
      List<AckEntryView> acknowledged,
      List<AckEntryView> pending,
      List<ClosedWorkorderEntryView> rated,
      List<ClosedWorkorderEntryView> unrated) {
  }

  // -------------------------------------------------------------------------
  // Work log ratings (17-4, blueprint C2, FR-121)
  // -------------------------------------------------------------------------

  /**
   * Rate a completed work log body (FR-121). {@code scores} maps criterion id → integer
   * within the criterion's min/max (default 1..5); the service validates ids and ranges
   * (missing criteria allowed). Unknown ids and out-of-range values → 400 VALIDATION_ERROR.
   * The rated technician is always derived from the work log — never from the request.
   */
  public record RateWorkLogRequest(@NotNull Map<UUID, Integer> scores) {
  }

  /** Read model for one persisted work-log rating row. */
  public record WorkLogRatingView(
      UUID id,
      UUID workLogId,
      String workOrderId,
      UUID criterionId,
      String criterionName,
      int score,
      UUID ratedBy,
      Instant ratedAt) {
  }

  /**
   * Create work-log rating criterion body (SUPER_ADMIN, AD-14). The score range must
   * satisfy {@code minScore < maxScore} with positive bounds (DB CHECK backstop).
   */
  public record CreateWorkLogRatingCriterionRequest(
      @NotBlank @Size(max = 200) String name,
      @Size(max = 4000) String description,
      @NotNull @Min(1) Integer minScore,
      @NotNull @Min(1) Integer maxScore,
      UUID plantId,
      Boolean active,
      @Min(0) @Max(100_000) Integer sortOrder) {
  }

  /** Update work-log rating criterion body (SUPER_ADMIN). */
  public record UpdateWorkLogRatingCriterionRequest(
      @NotBlank @Size(max = 200) String name,
      @Size(max = 4000) String description,
      @NotNull @Min(1) Integer minScore,
      @NotNull @Min(1) Integer maxScore,
      UUID plantId,
      Boolean active,
      @Min(0) @Max(100_000) Integer sortOrder) {
  }

  /** Read model for one work-log rating criterion row. */
  public record WorkLogRatingCriterionView(
      UUID id,
      String name,
      String description,
      int minScore,
      int maxScore,
      UUID plantId,
      boolean active,
      int sortOrder,
      Instant createdAt,
      Instant updatedAt) {
  }

  // -------------------------------------------------------------------------
  // Workorder quality ratings (17-5, blueprint C4-C6, FR-124)
  // -------------------------------------------------------------------------

  /**
   * Submit a workorder quality rating body (FR-124). The PRODUCTION_LEADER of the
   * affected line rates a CLOSED maintenance workorder with per-criterion scores and
   * per-technician scores via the pivot; the three headline scores are the legacy
   * cleanliness/tidiness/speed columns. One rating per workorder, immutable after
   * submission.
   */
  public record SubmitQualityRatingRequest(
      @NotNull @Size(min = 1) List<UUID> technicianIds,
      @NotNull Map<UUID, Integer> scores,
      @Min(1) @Max(5) Integer cleanlinessScore,
      @Min(1) @Max(5) Integer tidinessScore,
      @Min(1) @Max(5) Integer speedScore) {
  }

  /** Read model for a per-criterion score of a workorder quality rating. */
  public record QualityRatingScoreView(UUID criterionId, String criterionName, int score) {
  }

  /** Read model for a workorder quality rating with its scores and technicians. */
  public record QualityRatingView(
      UUID id,
      String workOrderId,
      WorkRatingStatus status,
      Integer cleanlinessScore,
      Integer tidinessScore,
      Integer speedScore,
      Instant dueAt,
      Instant submittedAt,
      UUID submittedBy,
      String remarks,
      List<QualityRatingScoreView> scores,
      List<UUID> technicianIds) {
  }

  /** Read model for one workorder rating criterion row (C3). */
  public record WorkOrderRatingCriterionView(
      UUID id,
      String name,
      String description,
      int minScore,
      int maxScore,
      UUID plantId,
      boolean active,
      int sortOrder) {
  }
}
