package com.syncro.integration.application;

import com.syncro.config.WebhookProperties;
import com.syncro.integration.domain.WebhookDeliveryStatus;
import com.syncro.integration.domain.WebhookDirection;
import com.syncro.integration.infrastructure.db.WebhookConfigEntity;
import com.syncro.integration.infrastructure.db.WebhookConfigRepository;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogEntity;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogRepository;
import com.syncro.maintenance.application.WorkOrderLifecycleEvent;
import com.syncro.notification.domain.AlertOpenedEvent;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AFTER_COMMIT enqueue listener for outbound webhooks (story 22-1, blueprint I4).
 *
 * <p>Mirrors {@code WorkOrderNotificationRoutingService}: it runs after the source event
 * commits, so a delivery row is never written inline with the request/ingest path and a
 * rolled-back business transaction never leaves a phantom delivery. One PENDING row is
 * enqueued per active OUTBOUND config whose {@code event_types} subscribes the event; the
 * idempotency key {@code configId:eventType:eventKey} makes a duplicate event a silent no-op.
 *
 * <p>Review 22-1 P1: each row is inserted in its OWN {@code REQUIRES_NEW} transaction after
 * an {@code existsByIdempotencyKey} pre-check. A swallowed {@code DataIntegrityViolationException}
 * marks its transaction rollback-only, so doing it inline would (a) silently drop the remaining
 * configs' rows in the same fan-out and (b) surface an {@code UnexpectedRollbackException} to the
 * business caller. The pre-check keeps the common duplicate path off the constraint entirely; the
 * per-row transaction makes the fan-out resilient; the DIV catch survives only as a race backstop.
 *
 * <p>Dispatch itself is the worker's job — this listener only enqueues.
 */
@Service
public class WebhookDispatchListener {

  private static final Logger log = LoggerFactory.getLogger(WebhookDispatchListener.class);
  private static final String IDEMPOTENCY_KEY_CONSTRAINT = "uq_webhook_delivery_logs_idempotency_key";
  private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

  /** Stable subscribed-event vocabulary for the OUTBOUND webhook surface (story 22-1). */
  public static final String EVENT_ALERT_OPENED = "ALERT_OPENED";

  private final WebhookConfigRepository configs;
  private final WebhookDeliveryLogRepository deliveries;
  private final WebhookProperties properties;
  private final Clock clock;
  private final TransactionTemplate requiresNew;

  public WebhookDispatchListener(WebhookConfigRepository configs,
      WebhookDeliveryLogRepository deliveries, WebhookProperties properties, Clock clock,
      PlatformTransactionManager transactionManager) {
    this.configs = configs;
    this.deliveries = deliveries;
    this.properties = properties;
    this.clock = clock;
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onWorkOrderLifecycle(WorkOrderLifecycleEvent event) {
    try {
      var payload = new LinkedHashMap<String, Object>();
      payload.put("eventType", event.eventType());
      payload.put("workOrderId", event.workOrderId());
      if (event.status() != null) {
        payload.put("status", event.status().name());
      }
      if (event.traceId() != null) {
        payload.put("traceId", event.traceId());
      }
      enqueue(event.eventType(), event.workOrderId(), event.traceId(), payload);
    } catch (Exception e) {
      log.error("[WEBHOOK][traceId={}] Failed to enqueue workorder delivery for {}: {}",
          event.traceId(), event.workOrderId(), e.getMessage(), e);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onAlertOpened(AlertOpenedEvent event) {
    try {
      var payload = new LinkedHashMap<String, Object>();
      payload.put("eventType", EVENT_ALERT_OPENED);
      payload.put("alertId", event.alertId().toString());
      if (event.machineId() != null) {
        payload.put("machineId", event.machineId().toString());
      }
      if (event.traceId() != null) {
        payload.put("traceId", event.traceId());
      }
      enqueue(EVENT_ALERT_OPENED, event.alertId().toString(), event.traceId(), payload);
    } catch (Exception e) {
      log.error("[WEBHOOK][traceId={}] Failed to enqueue alert delivery for {}: {}",
          event.traceId(), event.alertId(), e.getMessage(), e);
    }
  }

  /** One PENDING row per active OUTBOUND config subscribed to {@code eventType}. */
  private void enqueue(String eventType, String eventKey, String traceId,
      Map<String, Object> payload) {
    var now = Instant.now(clock);
    for (var config : activeOutboundConfigs()) {
      if (!subscribes(config, eventType)) {
        continue;
      }
      var key = idempotencyKey(config.getId(), eventType, eventKey);
      // Pre-check: the common duplicate path never touches the UNIQUE constraint, so no
      // transaction is ever marked rollback-only by a swallowed duplicate.
      if (deliveries.existsByIdempotencyKey(key)) {
        continue;
      }
      var row = new WebhookDeliveryLogEntity(
          UUID.randomUUID(), config.getId(), eventType, Map.copyOf(payload),
          null, null, null, WebhookDeliveryStatus.PENDING, 0, null,
          truncateTrace(traceId), properties.maxAttempts(), key, now, now);
      saveIgnoreDuplicate(row);
    }
  }

  private List<WebhookConfigEntity> activeOutboundConfigs() {
    return configs.findByDirectionAndActiveTrue(WebhookDirection.OUTBOUND);
  }

  private static boolean subscribes(WebhookConfigEntity config, String eventType) {
    var subscribed = config.getEventTypes();
    return subscribed != null && subscribed.contains(eventType);
  }

  private static String idempotencyKey(UUID configId, String eventType, String eventKey) {
    return configId + ":" + eventType + ":" + eventKey;
  }

  /**
   * Inserts one row in its own {@code REQUIRES_NEW} transaction. A duplicate caught here is
   * a race (two concurrent enqueues of the same event) — swallowed only when it is the
   * idempotency-key UNIQUE violation; any other integrity failure is rethrown so a delivery
   * is never dropped with zero evidence (review 22-1 P4). Package-private for the unit seam.
   */
  void saveIgnoreDuplicate(WebhookDeliveryLogEntity row) {
    try {
      requiresNew.executeWithoutResult(status -> deliveries.saveAndFlush(row));
    } catch (DataIntegrityViolationException exception) {
      if (isIdempotencyKeyViolation(exception)) {
        // duplicate event — idempotent skip (saveIgnoreDuplicate precedent)
        return;
      }
      log.error("[WEBHOOK][traceId={}] Non-idempotency integrity violation enqueueing delivery for config {}: {}",
          row.getTraceId(), row.getWebhookConfigId(), exception.getMostSpecificCause().getMessage(), exception);
      throw exception;
    }
  }

  private static boolean isIdempotencyKeyViolation(DataIntegrityViolationException exception) {
    var message = String.valueOf(exception.getMostSpecificCause().getMessage());
    if (message.contains(IDEMPOTENCY_KEY_CONSTRAINT)) {
      return true;
    }
    var cause = exception.getMostSpecificCause();
    return cause instanceof SQLException sqlException
        && UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState());
  }

  private static String truncateTrace(String traceId) {
    if (traceId == null) {
      return null;
    }
    return traceId.length() > 64 ? traceId.substring(0, 64) : traceId;
  }
}
