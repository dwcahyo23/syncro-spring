package com.syncro.alert.api;

import com.syncro.alert.domain.SparepartAlertStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SparepartAlertDtos {
  private SparepartAlertDtos() {
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
      @Schema(nullable = false) int thresholdPercentage,
      @Schema(nullable = false) long baselineCounter,
      @Schema(nullable = false) long expectedProductionCount,
      @Schema(nullable = false) long currentCounterSnapshot,
      @Schema(nullable = false) long consumedProductionCountSnapshot,
      @Schema(nullable = false) BigDecimal consumedPercentageSnapshot,
      @Schema(nullable = false) SparepartAlertStatus status,
      @Schema(nullable = true) String statusReason,
      @Schema(nullable = false) String traceId,
      @Schema(nullable = false) Instant createdAt,
      @Schema(nullable = false) Instant updatedAt) {
  }

  public record AlertListResponse(
      @Schema(nullable = false) List<AlertView> items,
      @Schema(nullable = false) long totalElements,
      @Schema(nullable = false) int page,
      @Schema(nullable = false) int size,
      @Schema(nullable = false) String sort) {
  }
}
