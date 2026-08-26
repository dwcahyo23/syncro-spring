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
      @Size(max = 4000) String description) {
  }

  public record UpdatePreventiveProgramRequest(
      @NotNull @Min(1) @Max(31) Integer dayOfMonth,
      @Min(1) @Max(12) Integer monthOfYear,
      @NotBlank @Size(max = 200) String title,
      @Size(max = 4000) String description,
      @NotNull Boolean active) {
  }

  public record PreventiveProgramView(UUID id, UUID machineId, PreventiveCategory category, ScheduleType scheduleType,
      int dayOfMonth, Integer monthOfYear, String title, String description, boolean active, UUID createdBy,
      Instant createdAt, Instant updatedAt) {
  }

  public record ShiftWindowView(int shiftNumber, String startTime, String endTime) {
  }

  public record MachineShiftConfigView(String source, boolean inheritedFromGroup, List<ShiftWindowView> shifts) {
  }

  public record PreventiveScheduleView(UUID id, UUID programId, UUID machineId, LocalDate dueDate, String status,
      String derivedStatus, Instant completedAt, UUID performedBy, String category, String scheduleType,
      MachineShiftConfigView shiftConfig, LocalDate today) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }
}