package com.syncro.masterdata.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlantDtos {
  private PlantDtos() {
  }

  public record PlantRequest(
      @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String code,
      @NotBlank @Size(max = 255) String name) {
  }

  public record PlantView(UUID id, String code, String name, Instant createdAt, Instant updatedAt) {
  }

  public record PlantListResponse(List<PlantView> items) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp, String traceId) {
  }
}
