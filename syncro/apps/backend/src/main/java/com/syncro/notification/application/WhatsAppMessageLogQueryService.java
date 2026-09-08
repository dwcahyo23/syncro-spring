package com.syncro.notification.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.notification.api.WhatsAppMessageLogDtos.MessageLogListResponse;
import com.syncro.notification.api.WhatsAppMessageLogDtos.MessageLogView;
import com.syncro.notification.domain.WhatsAppMessageLogStatus;
import com.syncro.notification.infrastructure.WhatsAppMessageLogEntity;
import com.syncro.notification.infrastructure.WhatsAppMessageLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SUPER_ADMIN-only paged reads over {@code whatsapp_message_logs} (story 22-4, AC3).
 * Mirrors 22-1's delivery reads: newest-first by sent_at, optional status/target_type/
 * work_order_id/trace_id filters, masked projections only — the service gate and the
 * rego {@code admin_only_paths} entry move together (parity-tested).
 */
@Service
public class WhatsAppMessageLogQueryService {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 100;

  private final WhatsAppMessageLogRepository messageLogs;

  public WhatsAppMessageLogQueryService(WhatsAppMessageLogRepository messageLogs) {
    this.messageLogs = messageLogs;
  }

  @Transactional(readOnly = true)
  public MessageLogListResponse list(AuthenticatedUser user, WhatsAppMessageLogStatus status,
      String targetType, String workOrderId, String traceId, int page, int size) {
    requireSuperAdmin(user);
    var normalizedPage = Math.max(page, 0);
    // Unsorted Pageable: the newest-first ordering (sent_at DESC NULLS LAST, logged_at
    // DESC, id DESC) is fixed in the repository query — a caller Sort would be appended
    // on top of it and re-break the NULL-first problem (review 22-4 P3).
    var pageable = PageRequest.of(normalizedPage, normalizeSize(size));
    var result = messageLogs.findLogs(status, blankToNull(targetType),
        blankToNull(workOrderId), blankToNull(traceId), pageable);
    return new MessageLogListResponse(
        result.getContent().stream().map(WhatsAppMessageLogQueryService::toView).toList(),
        result.getTotalElements(),
        result.getTotalPages(),
        normalizedPage,
        pageable.getPageSize(),
        "sentAt:desc:nullslast,loggedAt:desc,id:desc");
  }

  private void requireSuperAdmin(AuthenticatedUser user) {
    if (user == null || user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      throw new WhatsAppMessageLogForbiddenException();
    }
  }

  private int normalizeSize(int size) {
    if (size < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  static MessageLogView toView(WhatsAppMessageLogEntity entity) {
    return new MessageLogView(
        entity.getId(),
        entity.getDirection(),
        entity.getNotificationJobId(),
        entity.getTargetType(),
        entity.getTargetId(),
        entity.getTemplateName(),
        entity.getRecipientMasked(),
        entity.getStatus(),
        entity.getAttemptCount(),
        entity.getTraceId(),
        entity.getTextSha256(),
        entity.getWorkOrderId(),
        entity.getUserId(),
        entity.getIdempotencyKey(),
        entity.getSentAt(),
        entity.getLoggedAt(),
        entity.getReceivedAt(),
        entity.getVersion());
  }

  /** Service-level 403 — mapped to the house FORBIDDEN envelope by the module handler. */
  public static class WhatsAppMessageLogForbiddenException extends RuntimeException {
    public WhatsAppMessageLogForbiddenException() {
      super("WhatsApp message logs are SUPER_ADMIN-only.");
    }
  }
}
