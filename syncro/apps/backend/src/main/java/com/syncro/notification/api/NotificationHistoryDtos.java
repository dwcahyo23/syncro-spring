package com.syncro.notification.api;

import com.syncro.notification.domain.NotificationJobStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for alert notification history (Story 5.6).
 * Backend-driven view; frontend maps {@link NotificationJobStatus} to visual variant only.
 */
public final class NotificationHistoryDtos {

  private NotificationHistoryDtos() {
  }

  public record NotificationAttemptView(
      @Schema(description = "Attempt sequence number (1-based per job)", example = "1")
      int attemptNumber,
      @Schema(description = "Attempt status SENT or FAILED", example = "SENT")
      String status,
      @Schema(description = "Timestamp of the attempt (UTC ISO-8601)")
      Instant attemptedAt,
      @Schema(description = "Response detail truncated to 512 chars at write time", nullable = true)
      String responseDetail,
      @Schema(description = "Correlation trace id", nullable = true)
      String traceId) {
  }

  public record NotificationJobView(
      @Schema(nullable = false) UUID id,
      @Schema(nullable = false) UUID alertId,
      @Schema(description = "Escalation level TECHNICIAN|STAFF|LEADER|SPV|MANAGER", example = "TECHNICIAN", allowableValues = {"TECHNICIAN","STAFF","LEADER","SPV","MANAGER"})
      String escalationLevel,
      @Schema(nullable = false, description = "Job status enum")
      NotificationJobStatus status,
      @Schema(nullable = true, description = "Recipient user id (nullable for ROUTING_FAILED)")
      UUID recipientUserId,
      @Schema(nullable = true, description = "Display name resolved from auth_users.loginIdentifier")
      String recipientDisplayName,
      @Schema(nullable = true, description = "Masked phone for display (e.g. +62***890)")
      String recipientPhoneMasked,
      @Schema(nullable = false) int attemptCount,
      @Schema(nullable = false) int maxAttempts,
      @Schema(nullable = true, description = "Timestamp when WAHA send succeeded (SENT/ESCALATED)")
      Instant sentAt,
      @Schema(nullable = false) Instant createdAt,
      @Schema(nullable = false) Instant updatedAt,
      @Schema(nullable = true, description = "Next attempt timestamp for pending WAHA retry (nullable)")
      Instant nextAttemptAt,
      @Schema(nullable = true, description = "Error detail truncated to 512 chars (for failed/routing_failed)")
      String errorDetail,
      @Schema(nullable = true) String traceId,
      @Schema(description = "Attempt history 0..3 items ordered by attemptNumber asc")
      List<NotificationAttemptView> attempts) {
  }

  public record AlertNotificationHistoryResponse(
      @Schema(nullable = false) List<NotificationJobView> items,
      @Schema(nullable = false) int total) {
  }

  /**
   * Mask phone keeping first 3 and last 3 chars, mask middle with ***.
   * Example: +628123456789 -> +62***789
   * Null stays null; strings <=6 chars returned as-is.
   */
  public static String maskPhone(String phone) {
    if (phone == null) {
      return null;
    }
    String trimmed = phone.trim();
    if (trimmed.length() <= 6) {
      return trimmed;
    }
    return trimmed.substring(0, 3) + "***" + trimmed.substring(trimmed.length() - 3);
  }
}
