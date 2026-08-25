package com.syncro.org.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TeamDtos {
  private TeamDtos() {
  }

  public record CreateTeamRequest(
      @NotBlank @Size(max = 255) String name,
      @NotNull Instant expiresAt) {
  }

  public record UpdateTeamRequest(
      @NotBlank @Size(max = 255) String name,
      @NotNull Instant expiresAt) {
  }

  public record AddMemberRequest(@NotNull UUID userId) {
  }

  public record LinkMachineRequest(@NotNull UUID machineId) {
  }

  public record TeamView(
      UUID id,
      String name,
      Instant expiresAt,
      boolean active,
      long memberCount,
      long machineCount,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record TeamListResponse(List<TeamView> items) {
  }

  public record TeamMemberView(UUID userId, String loginIdentifier) {
  }

  public record TeamMachineView(
      UUID machineId,
      String code,
      String name,
      UUID plantId,
      String plantCode,
      UUID machineGroupId,
      String machineGroupName) {
  }

  public record TeamDetailResponse(
      UUID id,
      String name,
      Instant expiresAt,
      boolean active,
      List<TeamMemberView> members,
      List<TeamMachineView> machines,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }
}
