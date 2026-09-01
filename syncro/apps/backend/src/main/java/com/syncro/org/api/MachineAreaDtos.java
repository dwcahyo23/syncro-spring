package com.syncro.org.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MachineAreaDtos {
  private MachineAreaDtos() {
  }

  public record CreateMachineAreaRequest(
      @NotNull UUID plantId,
      @Size(max = 64) String code,
      @NotBlank @Size(max = 255) String name,
      @Size(max = 1000) String description) {
  }

  public record UpdateMachineAreaRequest(
      @Size(max = 64) String code,
      @NotBlank @Size(max = 255) String name,
      @Size(max = 1000) String description,
      @NotNull Boolean active) {
  }

  public record MachineAreaView(
      UUID id,
      UUID plantId,
      String plantCode,
      String plantName,
      String code,
      String name,
      String description,
      boolean active,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record MachineAreaListResponse(List<MachineAreaView> items) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }
}