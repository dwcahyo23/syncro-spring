package com.syncro.sparepart.api;

import jakarta.validation.constraints.NotNull;
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

  public record SparepartView(
      UUID id,
      String code,
      SparepartMachineRefView machine,
      SparepartTaxonomyRefView category,
      SparepartTaxonomyRefView brand,
      SparepartTaxonomyRefView kind,
      SparepartTaxonomyRefView type,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record SparepartListResponse(List<SparepartView> items, long totalElements, int page, int size, String sort) {
  }
}
