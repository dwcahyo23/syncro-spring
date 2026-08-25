package com.syncro.masterdata.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MachineGroupDtos {
  private MachineGroupDtos() {
  }

  public record MachineGroupRequest(
      @NotNull UUID plantId,
      @NotBlank @Size(max = 255) String name) {
  }

  public record MachineGroupSectionRequest(
      @NotNull UUID sectionId) {
  }

  public record MachineGroupView(
      UUID id,
      UUID plantId,
      String plantCode,
      String plantName,
      String name,
      UUID sectionId,
      String sectionCode,
      String sectionName,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record MachineGroupListResponse(List<MachineGroupView> items, long totalElements, int page, int size, String sort) {
  }
}
