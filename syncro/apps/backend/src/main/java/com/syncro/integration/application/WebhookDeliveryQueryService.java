package com.syncro.integration.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.integration.api.IntegrationDtos.DeliveryListResponse;
import com.syncro.integration.api.IntegrationDtos.DeliveryView;
import com.syncro.integration.domain.WebhookDeliveryStatus;
import com.syncro.integration.infrastructure.db.WebhookConfigRepository;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogEntity;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogRepository;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SUPER_ADMIN-only reads over {@code webhook_delivery_logs} (story 22-1, blueprint I4).
 * Delivery evidence is observable only to SUPER_ADMIN (rego {@code admin_only_paths}
 * parity); the projection never carries the HMAC secret or a full payload signature.
 */
@Service
public class WebhookDeliveryQueryService {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 100;

  private final WebhookDeliveryLogRepository deliveries;
  private final WebhookConfigRepository configs;

  public WebhookDeliveryQueryService(WebhookDeliveryLogRepository deliveries,
      WebhookConfigRepository configs) {
    this.deliveries = deliveries;
    this.configs = configs;
  }

  /**
   * Newest-first page of deliveries for one config (rides the config's admin_only path).
   * Review 22-1 P17: an unknown config id is 404, not an empty 200.
   */
  @Transactional(readOnly = true)
  public DeliveryListResponse listForConfig(AuthenticatedUser user, UUID configId, int page, int size) {
    requireSuperAdmin(user);
    if (!configs.existsById(configId)) {
      throw new WebhookConfigService.WebhookConfigNotFoundException();
    }
    var normalizedPage = Math.max(page, 0);
    var pageable = PageRequest.of(normalizedPage, normalizeSize(size),
        Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
    return toList(deliveries.findByWebhookConfigId(configId, pageable), normalizedPage, pageable.getPageSize());
  }

  /** Newest-first page of deliveries, optionally filtered by status. */
  @Transactional(readOnly = true)
  public DeliveryListResponse list(AuthenticatedUser user, WebhookDeliveryStatus status, int page,
      int size) {
    requireSuperAdmin(user);
    var normalizedPage = Math.max(page, 0);
    var pageable = PageRequest.of(normalizedPage, normalizeSize(size),
        Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
    var result = status == null
        ? deliveries.findAll(pageable)
        : deliveries.findByStatus(status, pageable);
    return toList(result, normalizedPage, pageable.getPageSize());
  }

  private DeliveryListResponse toList(org.springframework.data.domain.Page<WebhookDeliveryLogEntity> result,
      int page, int size) {
    return new DeliveryListResponse(
        result.getContent().stream().map(WebhookDeliveryQueryService::toView).toList(),
        result.getTotalElements(),
        result.getTotalPages(),
        page,
        size,
        "createdAt,id:desc");
  }

  private void requireSuperAdmin(AuthenticatedUser user) {
    if (user == null || user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      throw new WebhookConfigService.WebhookForbiddenException();
    }
  }

  private int normalizeSize(int size) {
    if (size < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  static DeliveryView toView(WebhookDeliveryLogEntity entity) {
    return new DeliveryView(
        entity.getId(),
        entity.getWebhookConfigId(),
        entity.getEventType(),
        entity.getStatus(),
        entity.getResponseCode(),
        entity.getResponseBody(),
        entity.getLatencyMs(),
        entity.getAttemptCount(),
        entity.getMaxAttempts(),
        entity.getNextRetryAt(),
        entity.getTraceId(),
        entity.getIdempotencyKey(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
