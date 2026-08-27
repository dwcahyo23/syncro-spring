package com.syncro.maintenance.preventive.api;

import com.syncro.maintenance.preventive.domain.PreventiveCategory;
import com.syncro.maintenance.preventive.domain.ScheduleType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PreventiveDtos {
  private PreventiveDtos() {
  }

  public record CreatePreventiveProgramRequest(
      @NotNull UUID machineId,
      @NotNull PreventiveCategory category,
      @NotNull ScheduleType scheduleType,
      @NotNull @Min(1) @Max(31) Integer dayOfMonth,
      @Min(1) @Max(12) Integer monthOfYear,
      @NotBlank @Size(max = 200) String title,
      @Size(max = 4000) String description,
      Boolean autoWorkorder) {
  }

  public record UpdatePreventiveProgramRequest(
      @NotNull @Min(1) @Max(31) Integer dayOfMonth,
      @Min(1) @Max(12) Integer monthOfYear,
      @NotBlank @Size(max = 200) String title,
      @Size(max = 4000) String description,
      @NotNull Boolean active,
      Boolean autoWorkorder) {
  }

  public record PreventiveProgramView(UUID id, UUID machineId, PreventiveCategory category, ScheduleType scheduleType,
      int dayOfMonth, Integer monthOfYear, String title, String description, boolean active, boolean autoWorkorder,
      UUID createdBy, Instant createdAt, Instant updatedAt) {
  }

  public record PreventiveReportItemView(int position, String label, String value, java.math.BigDecimal lsl,
      java.math.BigDecimal usl, String note) {
  }

  public record PreventiveReportEvidenceView(UUID id, String filename, String contentType, String presignedUrl) {
  }

  public record PreventiveReportView(UUID scheduleId, String programTitle, String category, String scheduleType,
      boolean autoWorkorder, UUID machineId, LocalDate dueDate, String scheduleStatus, Instant completedAt,
      UUID performedBy, String notes, String assessment, String signerIdentity, Instant approvedAt,
      List<PreventiveReportItemView> items, List<PreventiveReportEvidenceView> evidence,
      String signaturePresignedUrl, String workOrderId) {
  }

  public record ShiftWindowView(int shiftNumber, String startTime, String endTime) {
  }

  public record MachineShiftConfigView(String source, boolean inheritedFromGroup, List<ShiftWindowView> shifts) {
  }

  public record PreventiveScheduleView(UUID id, UUID programId, UUID machineId, LocalDate dueDate, String status,
      String derivedStatus, Instant completedAt, UUID performedBy, String category, String scheduleType,
      MachineShiftConfigView shiftConfig, LocalDate today, String checklistStatus) {
  }

  public record ChecklistItemRequest(@NotBlank @Size(max = 200) String label, String value,
      java.math.BigDecimal lsl, java.math.BigDecimal usl, @Size(max = 4000) String note) {
  }

  public record SubmitChecklistRequest(@Size(max = 4000) String notes,
      @NotNull @Size(min = 1) List<ChecklistItemRequest> items) {
  }

  public record ApproveScheduleRequest(@NotBlank @Size(max = 512) String signatureObjectKey,
      @Size(max = 200) String signerIdentity, @Size(max = 4000) String assessment) {
  }

  public record ChecklistResultView(UUID id, UUID scheduleId, UUID performedBy, Instant completedAt, String notes,
      UUID leaderId, String assessment, Instant approvedAt, String signatureObjectKey, String signerIdentity,
      List<ChecklistItemRequest> items) {
  }

  public record ChecklistView(String status, ChecklistResultView result) {
  }

  public record PreventiveAttachmentView(UUID id, UUID scheduleId, String filename, String contentType,
      String objectKey, long sizeBytes, UUID uploadedBy, Instant createdAt, Instant updatedAt,
      String presignedUrl) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }
}