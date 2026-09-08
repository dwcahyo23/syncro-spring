package com.syncro.integration.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.config.WebhookProperties;
import com.syncro.integration.infrastructure.WebhookHttpClient;
import com.syncro.integration.infrastructure.db.WebhookConfigRepository;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogEntity;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-row outbound webhook dispatch (story 22-1, blueprint I4).
 *
 * <p>Owns the delivery status machine: 2xx → DELIVERED; 4xx → FAILED (terminal,
 * non-retryable); 5xx/timeout → RETRYING with exponential backoff
 * {@code min(backoffBaseSeconds * 2^attemptCount, 3600)}s (the
 * NotificationDispatchService {@code min(2^attemptCount, 60)} minutes precedent);
 * attempts exhausted → DLQ (terminal, next_retry_at null); circuit-open skips do NOT
 * consume the attempt budget (NotificationJobEntity.markCircuitOpen precedent).
 *
 * <p>The HTTP call runs OUTSIDE any transaction; only the short state write is wrapped
 * in a {@link TransactionTemplate} (NotificationDispatchService precedent). The payload
 * is signed per-config with {@code X-Syncro-Signature: hex(HmacSHA256(secret, body))}
 * (JwtTokenService Mac precedent) — the secret and the signature never reach a log,
 * audit row, or the stored response body.
 */
@Service
public class WebhookDispatchService {

  private static final Logger log = LoggerFactory.getLogger(WebhookDispatchService.class);
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final long MAX_BACKOFF_SECONDS = 3_600L;

  private final WebhookDeliveryLogRepository deliveries;
  private final WebhookConfigRepository configs;
  private final WebhookHttpClient httpClient;
  private final WebhookProperties properties;
  // Boot 4 auto-configures Jackson 3, so no com.fasterxml ObjectMapper bean exists —
  // inline instantiation mirrors AuditLogWriter/JwtTokenService.
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public WebhookDispatchService(WebhookDeliveryLogRepository deliveries,
      WebhookConfigRepository configs,
      WebhookHttpClient httpClient,
      WebhookProperties properties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.deliveries = deliveries;
    this.configs = configs;
    this.httpClient = httpClient;
    this.properties = properties;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /** Dispatches one due delivery row. Never throws — every failure lands in the row. */
  public void dispatch(WebhookDeliveryLogEntity delivery) {
    var configOpt = configs.findById(delivery.getWebhookConfigId());
    if (configOpt.isEmpty()) {
      // FK is ON DELETE CASCADE and configs are never deleted — defensive terminal state.
      Instant now = Instant.now(clock);
      transactionTemplate.executeWithoutResult(status -> {
        delivery.markDlq(null, "Webhook config no longer exists", null, now);
        deliveries.save(delivery);
      });
      return;
    }
    var config = configOpt.get();
    if (!config.isActive()) {
      // Review 22-1 P5: deactivation must stop deliveries — a queued row for a now-inactive
      // config is dead-lettered (terminal) rather than POSTed to a subscriber that opted out.
      Instant now = Instant.now(clock);
      transactionTemplate.executeWithoutResult(status -> {
        delivery.markDlq(null, "config deactivated", null, now);
        deliveries.save(delivery);
      });
      log.info("[WEBHOOK][traceId={}] Delivery {} DLQ — config {} deactivated",
          delivery.getTraceId(), delivery.getId(), config.getId());
      return;
    }

    String body;
    try {
      body = objectMapper.writeValueAsString(delivery.getPayload());
    } catch (JsonProcessingException e) {
      Instant now = Instant.now(clock);
      log.error("[WEBHOOK][traceId={}] Delivery {} payload serialization failed: {}",
          delivery.getTraceId(), delivery.getId(), e.getMessage());
      transactionTemplate.executeWithoutResult(status -> {
        delivery.markDlq(null, "Payload serialization failed", null, now);
        deliveries.save(delivery);
      });
      return;
    }

    Instant start = Instant.now(clock);
    var result = httpClient.post(config.getEndpointUrl(), body,
        signature(config.getHmacSecret(), body), delivery.getTraceId());
    // Latency measured on the injected clock — a fixed test clock yields 0 ms deterministically.
    int latencyMs = (int) Math.max(0, Duration.between(start, Instant.now(clock)).toMillis());

    Instant now = Instant.now(clock);
    if (result.success()) {
      transactionTemplate.executeWithoutResult(status -> {
        delivery.markDelivered(result.httpStatus(), result.detail(), latencyMs, now);
        deliveries.save(delivery);
      });
      log.info("[WEBHOOK][traceId={}] Delivery {} DELIVERED status={}",
          delivery.getTraceId(), delivery.getId(), result.httpStatus());
      return;
    }

    if (isCircuitOpen(result)) {
      // Dependency state, not a delivery failure: no attempt consumed, retried after
      // the breaker's open wait (NotificationJobEntity.markCircuitOpen precedent).
      Instant retryAt = now.plus(properties.resilience().waitDurationInOpenState());
      transactionTemplate.executeWithoutResult(status -> {
        delivery.markCircuitOpen(retryAt, now);
        deliveries.save(delivery);
      });
      log.warn("[WEBHOOK][traceId={}] Delivery {} circuit OPEN — retryAt={}",
          delivery.getTraceId(), delivery.getId(), retryAt);
      return;
    }

    // Review 22-1 P12: only 5xx / timeout / transport failure are retryable. 4xx is a
    // deterministic client error and 3xx is an unexpected redirect — neither heals on
    // retry, so both are terminal FAILED (the config is fixed and a new event re-delivers).
    boolean retryable = result.httpStatus() == 0 || result.httpStatus() >= 500;
    if (!retryable) {
      transactionTemplate.executeWithoutResult(status -> {
        delivery.markFailed(result.httpStatus(), result.detail(), latencyMs, now);
        deliveries.save(delivery);
      });
      log.warn("[WEBHOOK][traceId={}] Delivery {} FAILED (terminal) status={}",
          delivery.getTraceId(), delivery.getId(), result.httpStatus());
      return;
    }

    // 5xx / timeout / transport failure — retry with exponential backoff until exhausted.
    int nextAttemptCount = delivery.getAttemptCount() + 1;
    if (nextAttemptCount >= delivery.getMaxAttempts()) {
      transactionTemplate.executeWithoutResult(status -> {
        delivery.markDlq(result.httpStatus(), result.detail(), latencyMs, now);
        deliveries.save(delivery);
      });
      log.warn("[WEBHOOK][traceId={}] Delivery {} DLQ after {} attempts: HTTP {}",
          delivery.getTraceId(), delivery.getId(), nextAttemptCount, result.httpStatus());
      return;
    }
    Instant retryAt = now.plusSeconds(computeBackoffSeconds(delivery.getAttemptCount()));
    transactionTemplate.executeWithoutResult(status -> {
      delivery.markRetrying(result.httpStatus(), result.detail(), latencyMs, retryAt, now);
      deliveries.save(delivery);
    });
    log.warn("[WEBHOOK][traceId={}] Delivery {} RETRYING attempt {}/{}: HTTP {} retryAt={}",
        delivery.getTraceId(), delivery.getId(), nextAttemptCount, delivery.getMaxAttempts(),
        result.httpStatus(), retryAt);
  }

  /** {@code min(base * 2^attemptCount, 3600)} seconds — 60 s base = the minutes precedent. */
  private long computeBackoffSeconds(int currentAttemptCount) {
    long backoff = (long) Math.min(
        (double) properties.backoffBaseSeconds() * Math.pow(2, currentAttemptCount),
        MAX_BACKOFF_SECONDS);
    return Math.max(1, backoff);
  }

  private static boolean isCircuitOpen(WebhookHttpClient.Result result) {
    return !result.success()
        && result.httpStatus() == 0
        && WebhookHttpClient.CIRCUIT_OPEN_DETAIL.equals(result.detail());
  }

  /** {@code X-Syncro-Signature: hex(HmacSHA256(secret, body))} — JwtTokenService Mac precedent. */
  static String signature(String secret, String body) {
    try {
      var mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(
          (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      // Never include the secret or the exception detail in the message.
      throw new IllegalStateException("Unable to sign webhook payload");
    }
  }
}
