package com.syncro.sparepart.api;

import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SparepartTaxonomyDtos {
  private SparepartTaxonomyDtos() {
  }

  public record SparepartTaxonomyRequest(
      @NotNull SparepartTaxonomyDimension dimension,
      @NotBlank @Size(max = 255) String name) {
  }

  public record SparepartTaxonomyView(
      UUID id,
      SparepartTaxonomyDimension dimension,
      String name,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record SparepartTaxonomyListResponse(List<SparepartTaxonomyView> items) {
  }
}
