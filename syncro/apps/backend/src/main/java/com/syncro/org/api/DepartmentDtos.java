package com.syncro.org.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DepartmentDtos {
  private DepartmentDtos() {
  }

  public record CreateDepartmentRequest(
      @NotNull UUID plantId,
      @NotBlank @Size(max = 255) String name,
      UUID spvId,
      UUID mgId) {
  }

  public record UpdateDepartmentRequest(
      @NotBlank @Size(max = 255) String name,
      UUID spvId,
      UUID mgId,
      @NotNull Boolean active) {
  }

  public record SetDepartmentMembersRequest(@NotNull List<UUID> userIds) {
  }

  public record DepartmentView(
      UUID id,
      UUID plantId,
      String plantCode,
      String plantName,
      String name,
      UUID spvId,
      UUID mgId,
      boolean active,
      long memberCount,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record DepartmentListResponse(List<DepartmentView> items) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }
}
