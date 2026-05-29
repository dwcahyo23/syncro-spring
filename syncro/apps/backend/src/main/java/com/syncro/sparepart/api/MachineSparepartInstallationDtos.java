package com.syncro.sparepart.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MachineSparepartInstallationDtos {
  private MachineSparepartInstallationDtos() {
  }

  public record InstallationRequest(
      @NotNull UUID machineId,
      @NotNull UUID sparepartId,
      @NotNull @Min(1) Long expectedProductionCount,
      @NotNull @Min(0) Long baselineCounter,
      @Min(1) @Max(100) Integer thresholdPercentage) {
  }

  public record InstallationUpdateRequest(
      @NotNull @Min(1) Long expectedProductionCount,
      @NotNull @Min(0) Long baselineCounter,
      @Min(1) @Max(100) Integer thresholdPercentage) {
  }

  public record TaxonomyRefView(UUID id, String code, String name) {
  }

  public record InstallationView(UUID id, UUID machineId, String machineCode, String machineName, UUID plantId,
      String plantCode, String plantName, UUID machineGroupId, String machineGroupName, UUID sparepartId,
      String sparepartCode, String sparepartName, TaxonomyRefView category, TaxonomyRefView brand,
      TaxonomyRefView kind, TaxonomyRefView type, long expectedProductionCount, long baselineCounter,
      Long currentCount, Long consumedProductionCount, BigDecimal consumedPercentage, int thresholdPercentage,
      String calculationBasis, Instant installedAt, Instant createdAt, Instant updatedAt) {
  }

  public record InstallationListResponse(List<InstallationView> items) {
  }
}
