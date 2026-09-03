package com.syncro.maintenance.preventive.api;

import com.syncro.maintenance.preventive.domain.PmItemInputType;
import com.syncro.maintenance.preventive.domain.PmScheduleDateStatus;
import com.syncro.maintenance.preventive.domain.PmScheduleStatus;
import com.syncro.maintenance.preventive.domain.PreventiveCategory;
import com.syncro.maintenance.preventive.domain.ScheduleType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
      boolean autoWorkorder, UUID machineId, UUID plantId, UUID machineGroupId, LocalDate dueDate,
      String scheduleStatus, Instant completedAt, UUID performedBy, String notes, String assessment,
      String signerIdentity, Instant approvedAt, List<PreventiveReportItemView> items,
      List<PreventiveReportEvidenceView> evidence, String signaturePresignedUrl, String workOrderId) {
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

  // -------------------------------------------------------------------------
  // PM Frequencies (story 19-1)
  // -------------------------------------------------------------------------

  public record CreateFrequencyRequest(
      @NotBlank @Size(max = 50) String code,
      @NotBlank @Size(max = 200) String name,
      @Size(max = 4000) String description,
      Integer sortOrder,
      Boolean isActive) {
  }

  public record UpdateFrequencyRequest(
      @NotBlank @Size(max = 200) String name,
      @Size(max = 4000) String description,
      Integer sortOrder,
      Boolean isActive) {
  }

  public record FrequencyView(UUID id, String code, String name, String description,
      int sortOrder, boolean isActive, Instant createdAt, Instant updatedAt) {
  }

  // -------------------------------------------------------------------------
  // PM Checksheets (story 19-1)
  // -------------------------------------------------------------------------

  public record CreateChecksheetRequest(
      @NotNull UUID machineId,
      @NotNull UUID frequencyId,
      @Size(max = 4000) String revisionReason) {
  }

  public record ReviseChecksheetRequest(
      @Size(max = 4000) String revisionReason) {
  }

  public record ApproveChecksheetRequest(
      LocalDate effectiveDate) {
  }

  public record ChecksheetView(UUID id, UUID machineId, UUID frequencyId, int revisionNo,
      String revisionReason, boolean isActive, UUID supersedes, UUID approvedBy,
      Instant approvedAt, LocalDate effectiveDate, UUID createdBy, Instant createdAt,
      Instant updatedAt) {
  }

  public record ActiveChecksheetView(UUID checksheetId, UUID machineId, UUID frequencyId,
      int revisionNo) {
  }

  // -------------------------------------------------------------------------
  // PM checklist categories & items (story 19-2)
  // -------------------------------------------------------------------------

  public record CreateChecklistCategoryRequest(
      @NotNull UUID checksheetId,
      @NotBlank @Size(max = 200) String name,
      Integer sortOrder) {
  }

  public record UpdateChecklistCategoryRequest(
      @NotBlank @Size(max = 200) String name,
      Integer sortOrder) {
  }

  public record ChecklistCategoryView(UUID id, UUID checksheetId, String name, int sortOrder,
      Instant createdAt, Instant updatedAt) {
  }

  public record CreateChecklistItemRequest(
      @NotNull UUID checksheetId,
      UUID categoryId,
      Integer sequence,
      @NotBlank @Size(max = 500) String parameterText,
      @Size(max = 200) String checkMethod,
      @NotNull PmItemInputType inputType,
      @Size(max = 50) String unit,
      BigDecimal lsl,
      BigDecimal nominal,
      BigDecimal usl,
      Boolean isCriticalFlag,
      @Size(max = 255) String referenceDocument,
      UUID calibrationInstrumentId) {
  }

  public record UpdateChecklistItemRequest(
      UUID categoryId,
      Integer sequence,
      @NotBlank @Size(max = 500) String parameterText,
      @Size(max = 200) String checkMethod,
      @NotNull PmItemInputType inputType,
      @Size(max = 50) String unit,
      BigDecimal lsl,
      BigDecimal nominal,
      BigDecimal usl,
      Boolean isCriticalFlag,
      @Size(max = 255) String referenceDocument,
      UUID calibrationInstrumentId) {
  }

  public record ChecklistItemView(UUID id, UUID checksheetId, UUID categoryId, int sequence,
      String parameterText, String checkMethod, PmItemInputType inputType, String unit,
      BigDecimal lsl, BigDecimal nominal, BigDecimal usl, boolean isCriticalFlag,
      String referenceDocument, UUID calibrationInstrumentId, Instant createdAt,
      Instant updatedAt) {
  }

  // -------------------------------------------------------------------------
  // PM schedules & schedule dates (story 19-3)
  // -------------------------------------------------------------------------

  public record CreateScheduleRequest(
      @NotNull UUID plantId,
      @NotNull UUID machineId,
      @NotNull UUID checksheetId,
      @NotNull @Min(2000) @Max(2999) Integer year) {
  }

  public record ScheduleDateTransitionRequest(
      @NotNull PmScheduleDateStatus status) {
  }

  public record ScheduleView(UUID id, UUID plantId, UUID machineId, UUID checksheetId,
      int checksheetRevisionNo, UUID frequencyId, String frequencyCode, String frequencyName,
      int year, PmScheduleStatus status, UUID submittedBy, Instant submittedAt,
      UUID approvedBySpv, Instant approvedAtSpv, UUID approvedByProd, Instant approvedAtProd,
      Map<String, Object> warnings, Instant createdAt, Instant updatedAt,
      List<ScheduleDateView> dates) {
  }

  public record ScheduleDateView(UUID id, UUID scheduleId, LocalDate plannedDate,
      PmScheduleDateStatus status, Instant createdAt, Instant updatedAt) {
  }
}
