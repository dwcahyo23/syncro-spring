package com.syncro.sparepart.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MachineSparepartInstallationDtos {
  private MachineSparepartInstallationDtos() {
  }

  public record InstallationRequest(
      @NotNull @Schema(description = "Machine the sparepart is installed on", nullable = false) UUID machineId,
      @NotNull @Schema(description = "Sparepart being installed", nullable = false) UUID sparepartId,
      @NotBlank @Size(max = 255) @Schema(description = "Function or usage of the sparepart", nullable = false, maxLength = 255) String functionName,
      @NotNull @Min(1) @Schema(description = "Expected production count", nullable = false, minimum = "1") Long expectedProductionCount,
      @NotNull @Min(0) @Schema(description = "Baseline counter at installation time", nullable = false, minimum = "0") Long baselineCounter,
      @Min(1) @Max(100) @Schema(description = "Consumed lifetime threshold percentage, defaults to 90", nullable = true, minimum = "1", maximum = "100") Integer thresholdPercentage,
      @Schema(description = "Actual installation date; defaults to now when omitted", nullable = true) Instant installedAt) {
  }

  public record InstallationUpdateRequest(
      @NotBlank @Size(max = 255) @Schema(description = "Function or usage of the sparepart", nullable = false, maxLength = 255) String functionName,
      @NotNull @Min(1) @Schema(description = "Expected production count", nullable = false, minimum = "1") Long expectedProductionCount,
      @NotNull @Min(0) @Schema(description = "Baseline counter", nullable = false, minimum = "0") Long baselineCounter,
      @Min(1) @Max(100) @Schema(description = "Threshold percentage; null keeps the current value", nullable = true, minimum = "1", maximum = "100") Integer thresholdPercentage) {
  }

  public record TaxonomyRefView(@Schema(nullable = false) UUID id, @Schema(nullable = false) String code,
      @Schema(nullable = false) String name) {
  }

  public record InstallationView(@Schema(nullable = false) UUID id, @Schema(nullable = false) UUID machineId,
      @Schema(nullable = false) String machineCode, @Schema(nullable = false) String machineName,
      @Schema(nullable = false) UUID plantId, @Schema(nullable = false) String plantCode,
      @Schema(nullable = false) String plantName, @Schema(nullable = false) UUID machineGroupId,
      @Schema(nullable = false) String machineGroupName, @Schema(nullable = false) UUID sparepartId,
      @Schema(nullable = false) String sparepartCode, @Schema(nullable = false) String sparepartName,
      @Schema(nullable = false) String functionName, @Schema(nullable = false) TaxonomyRefView category,
      @Schema(nullable = false) TaxonomyRefView brand, @Schema(nullable = false) TaxonomyRefView kind,
      @Schema(nullable = false) TaxonomyRefView type, @Schema(nullable = false) long expectedProductionCount,
      @Schema(nullable = false) long baselineCounter, @Schema(nullable = true) Long currentCount,
      @Schema(nullable = true) Long consumedProductionCount, @Schema(nullable = true) BigDecimal consumedPercentage,
      @Schema(nullable = false) int thresholdPercentage, @Schema(nullable = false) String calculationBasis,
      @Schema(nullable = false) Instant installedAt, @Schema(nullable = false) Instant createdAt,
      @Schema(nullable = false) Instant updatedAt) {
  }

  public record InstallationListResponse(@Schema(nullable = false) List<InstallationView> items,
      @Schema(nullable = false) long totalElements, @Schema(nullable = false) int page,
      @Schema(nullable = false) int size, @Schema(nullable = false) String sort) {
  }
}
