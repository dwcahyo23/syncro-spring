package com.syncro.machine.api;

import com.syncro.machine.domain.MachineStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class MachineDtos {
  private MachineDtos() {
  }

  public record MachineRequest(
      @NotNull UUID plantId,
      @NotNull UUID machineGroupId,
      @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$") String code,
      @Size(max = 255) String name,
      @NotNull MachineStatus status,
      @Size(max = 255) String brand,
      LocalDate installedAt,
      @Size(max = 1000) String notes,
      @Size(max = 10) List<String> optionalTelemetryFields) {
  }

  public record MachineView(
      UUID id,
      UUID plantId,
      String plantCode,
      String plantName,
      UUID machineGroupId,
      String machineGroupName,
      String code,
      String name,
      MachineStatus status,
      String brand,
      LocalDate installedAt,
      String notes,
      Instant createdAt,
      Instant updatedAt,
      List<String> optionalTelemetryFields) {
  }

  public record MachineListResponse(List<MachineView> items, long totalElements, int page, int size, String sort) {
  }
}
