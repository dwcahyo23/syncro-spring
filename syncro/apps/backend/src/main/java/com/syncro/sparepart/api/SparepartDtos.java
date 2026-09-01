package com.syncro.sparepart.api;

import com.syncro.sparepart.domain.BomReviewStatus;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SparepartDtos {
  private SparepartDtos() {
  }

  public record SparepartRequest(
      @NotNull UUID machineId,
      @NotNull UUID categoryId,
      @NotNull UUID brandId,
      @NotNull UUID kindId,
      @NotNull UUID typeId) {
  }

  /**
   * Procurement-readiness subset (Story 8-2). Both fields are nullable: JSON null (or an
   * omitted key, indistinguishable after record binding) clears the value. Clients always
   * send both keys.
   */
  public record SparepartProcurementRequest(
      @Size(max = 64, message = "Material code must be at most 64 characters.") String materialCode,
      @Positive @Digits(integer = 10, fraction = 2) BigDecimal leadTimeHours) {
  }

  public record SparepartMachineRefView(
      UUID id,
      String code,
      String name,
      UUID plantId,
      String plantCode,
      String plantName) {
  }

  public record SparepartTaxonomyRefView(
      UUID id,
      String code,
      String name) {
  }

  /** BOM review request body (Story 18-1) — used by the reject endpoint; approve has no body. */
  public record BomReviewRequest(
      @NotBlank(message = "Rejection reason must not be blank.")
      @Size(max = 2000, message = "Rejection reason must be at most 2000 characters.")
      String rejectionReason) {
  }

  public record SparepartView(
      UUID id,
      String code,
      SparepartMachineRefView machine,
      SparepartTaxonomyRefView category,
      SparepartTaxonomyRefView brand,
      SparepartTaxonomyRefView kind,
      SparepartTaxonomyRefView type,
      String materialCode,
      BigDecimal leadTimeHours,
      String hierarchyIdentityKey,
      String bomSerial,
      String bomCode,
      Integer bomCodeVersion,
      BomReviewStatus reviewStatus,
      String rejectionReason,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record SparepartListResponse(List<SparepartView> items, long totalElements, int page, int size, String sort) {
  }
}
