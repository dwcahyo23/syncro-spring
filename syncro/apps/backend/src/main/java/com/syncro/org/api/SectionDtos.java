package com.syncro.org.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SectionDtos {
  private SectionDtos() {
  }

  public record CreateSectionRequest(
      @NotNull UUID plantId,
      @NotBlank @Size(max = 24) @Pattern(regexp = "^(?i)(MACHINERY|UTILITY|WORKSHOP)$", message = "Must be MACHINERY, UTILITY, or WORKSHOP.") String code,
      @NotBlank @Size(max = 255) String name) {
  }

  public record UpdateSectionRequest(
      @NotBlank @Size(max = 255) String name,
      @NotNull Boolean active) {
  }

  public record SectionView(
      UUID id,
      UUID plantId,
      String plantCode,
      String plantName,
      String code,
      String name,
      boolean active,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record SectionListResponse(List<SectionView> items) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }
}
