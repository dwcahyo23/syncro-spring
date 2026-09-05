package com.syncro.auth.api;

import com.syncro.auth.domain.ApplicationRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuthDtos {
  private AuthDtos() {
  }

  public record LoginRequest(@NotBlank String loginIdentifier, @NotBlank String password) {
  }

  /** Story 22-2: one login-attempt audit row (I2) — no credential material exists to mask. */
  public record LoginAuditView(UUID id, UUID userId, String identifier, String ipAddress,
      String userAgent, boolean wasSuccess, String failureReason, Instant occurredAt) {
  }

  /** House pagination shape (AuditLogListResponse precedent) + id-DESC tiebreaker sort. */
  public record LoginAuditListResponse(List<LoginAuditView> items, long totalElements, int totalPages, int page,
      int size, String sort) {
  }

  /** Story 22-2: issue a phone-verification challenge (SUPER_ADMIN, operator-driven). */
  public record IssuePhoneChallengeRequest(
      @NotNull UUID userId,
      @NotBlank @Pattern(regexp = "\\+?[0-9][0-9\\- ]{6,31}", message = "must be a phone number")
      @Size(max = 32) String phoneNumber) {
  }

  /** Story 22-2: verify an issued challenge with the delivered OTP. */
  public record VerifyPhoneChallengeRequest(@NotBlank @Pattern(regexp = "[0-9]{6}", message = "must be a 6-digit OTP") String otp) {
  }

  /** Story 22-2: challenge state — id + expiry only; the OTP and its hash are never returned. */
  public record PhoneChallengeView(UUID id, UUID userId, String pendingPhone, Instant expiresAt,
      int attemptCount, int maxAttempts, Instant resendAvailableAt, Instant consumedAt, Instant createdAt) {
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
