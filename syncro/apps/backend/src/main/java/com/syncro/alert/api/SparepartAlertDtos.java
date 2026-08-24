package com.syncro.alert.api;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.domain.SparepartAlertType;
import com.syncro.projection.application.CounterRateEstimator.CalculationBasis;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

public final class SparepartAlertDtos {
  private SparepartAlertDtos() {
  }

  /**
   * Summary of the most relevant notification job for an alert.
   * Null when the alert has no notification jobs at all.
   */
  public record NotificationSummary(
      @Schema(nullable = false) String status,
      @Schema(nullable = true) @Nullable String escalationLevel,
      @Schema(nullable = true) @Nullable Instant sentAt,
      @Schema(nullable = true) @Nullable String errorDetail) {
  }

  public record AlertView(
      @Schema(nullable = false) UUID id,
      @Schema(nullable = false) UUID machineId,
      @Schema(nullable = false) String machineCode,
      @Schema(nullable = true) String machineName,
      @Schema(nullable = false) UUID plantId,
      @Schema(nullable = false) String plantCode,
      @Schema(nullable = false) String plantName,
      @Schema(nullable = false) UUID machineGroupId,
      @Schema(nullable = false) String machineGroupName,
      @Schema(nullable = false) UUID installationId,
      @Schema(nullable = false) UUID sparepartId,
      @Schema(nullable = false) String sparepartCode,
      @Schema(nullable = false) String sparepartName,
      @Schema(nullable = false) String functionName,
      @Schema(nullable = false) SparepartAlertType alertType,
      @Schema(nullable = true) @Nullable Integer thresholdPercentage,
      @Schema(nullable = false) long baselineCounter,
      @Schema(nullable = false) long expectedProductionCount,
      @Schema(nullable = true) @Nullable Long currentCounterSnapshot,
      @Schema(nullable = true) @Nullable Long consumedProductionCountSnapshot,
      @Schema(nullable = true) @Nullable BigDecimal consumedPercentageSnapshot,
      @Schema(nullable = false) SparepartAlertStatus status,
      @Schema(nullable = true) String statusReason,
      @Schema(nullable = false) String traceId,
      @Schema(nullable = false) Instant createdAt,
      @Schema(nullable = false) Instant updatedAt,
      @Schema(nullable = true) @Nullable BigDecimal leadTimeHours,
      @Schema(nullable = true) @Nullable BigDecimal ratePerOperatingHour,
      @Schema(nullable = true) @Nullable CalculationBasis calculationBasis,
      @Schema(nullable = true) @Nullable Instant projectedDepletionAt,
      @Schema(nullable = true) @Nullable NotificationSummary notificationSummary) {
  }

  public record AlertListResponse(
      @Schema(nullable = false) List<AlertView> items,
      @Schema(nullable = false) long totalElements,
      @Schema(nullable = false) int page,
      @Schema(nullable = false) int size,
      @Schema(nullable = false) String sort) {
  }

  public record AcknowledgeRequest(
      @Schema(nullable = true, description = "Optional reason for acknowledging the alert") String reason) {
  }

  public record ResolveRequest(
      @Schema(nullable = true, description = "Optional reason for resolving the alert") String reason) {
  }

  public record ResolveOverrideRequest(
      @Schema(nullable = true, description = "Optional reason for SUPER_ADMIN direct resolve override") String reason) {
  }
}
