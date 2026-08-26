package com.syncro.maintenance.api;

import com.syncro.maintenance.domain.workorder.FmeaFailureType;
import com.syncro.maintenance.domain.workorder.StopTimeReason;
import com.syncro.maintenance.domain.workorder.TodoStatus;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
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

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }
}
