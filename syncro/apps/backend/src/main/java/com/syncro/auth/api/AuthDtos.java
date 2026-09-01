package com.syncro.auth.api;

import com.syncro.auth.domain.ApplicationRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class AuthDtos {
  private AuthDtos() {
  }

  public record LoginRequest(@NotBlank String loginIdentifier, @NotBlank String password) {
  }

  public record AuthUserView(String id, String loginIdentifier, String displayName, String nik, String phoneNumber,
      ApplicationRole applicationRole, boolean enabled, UUID jobTitleId, UUID departmentId,
      boolean forcePasswordChange, int failedLoginAttempts, String lockedAt, String lockReason,
      String phoneVerifiedAt) {
  }

  public record UpdateUserRequest(
      @Size(max = 200) String displayName,
      @Size(max = 50) String nik,
      @Size(max = 32) String phoneNumber,
      UUID jobTitleId,
      UUID departmentId) {
  }

  public record LoginResponse(String tokenType, String accessToken, long expiresInSeconds, AuthUserView user) {
  }

  public record PlantScopeView(String id, String code, String name) {
  }

  public record PlantScopeResponse(String mode, List<PlantScopeView> availablePlants, String defaultPlantId,
      String emptyReason) {
  }

  public record ErrorResponse(String code, String message, String timestamp, String traceId) {
  }
}
