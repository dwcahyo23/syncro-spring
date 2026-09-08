package com.syncro.notification.api;

import com.syncro.notification.domain.WhatsAppMessageDirection;
import com.syncro.notification.domain.WhatsAppMessageLogStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the outbound WhatsApp message-log evidence view (story 22-4).
 * Masked projections only: {@code recipientMasked} is first3+last3, {@code textSha256}
 * is a hash reference — no raw phone, raw message text, or WAHA secret ever appears.
 */
public final class WhatsAppMessageLogDtos {

  private WhatsAppMessageLogDtos() {
  }

  public record MessageLogView(
      @Schema(nullable = false) UUID id,
      @Schema(nullable = false, description = "Row direction; story 22-4 writes OUTBOUND only")
      WhatsAppMessageDirection direction,
      @Schema(nullable = true, description = "Drill-down link to notification_jobs (AC3)")
      UUID notificationJobId,
      @Schema(nullable = true, description = "ALERT | WORK_ORDER | SPAREPART_REQUEST")
      String targetType,
      @Schema(nullable = true, description = "Target identifier (alertId/woId/requestId)")
      String targetId,
      @Schema(nullable = true, description = "Derived template marker (never null for outbound rows)")
      String templateName,
      @Schema(nullable = true, description = "Masked recipient phone (e.g. 628***890)")
      String recipientMasked,
      @Schema(nullable = true, description = "SENT | FAILED")
      WhatsAppMessageLogStatus status,
      @Schema(nullable = false, description = "Dispatches recorded on this row (1-based)")
      int attemptCount,
      @Schema(nullable = true) String traceId,
      @Schema(nullable = true, description = "SHA-256 hex of the rendered text; null on render-fail")
      String textSha256,
      @Schema(nullable = true, description = "Workorder correlation (lifecycle/ack sends)")
      String workOrderId,
      @Schema(nullable = true, description = "Recipient user id")
      UUID userId,
      @Schema(nullable = true, description = "Raw job idempotency key — derivation is recoverable")
      String idempotencyKey,
      @Schema(nullable = true, description = "Latest successful send (UTC ISO-8601)")
      Instant sentAt,
      @Schema(nullable = false, description = "Row-creation instant (UTC ISO-8601); set once, never updated")
      Instant loggedAt,
      @Schema(nullable = true, description = "Inbound ingest timestamp; null for outbound rows")
      Instant receivedAt,
      @Schema(nullable = false) long version) {
  }

  /** House pagination shape (DeliveryListResponse precedent). */
  public record MessageLogListResponse(
      List<MessageLogView> items,
      long totalElements,
      int totalPages,
      int page,
      int size,
      String sort) {
  }
}
