package com.syncro.integration.infrastructure.db;

import com.syncro.integration.domain.WebhookDeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted {@code webhook_delivery_logs} row (blueprint I4, story 15-2; dispatch
 * wiring in story 22-1). One delivery attempt stream per webhook config: payload
 * JSONB, HTTP response, latency, retry bookkeeping. {@code next_retry_at} backs the
 * retry sweep; DLQ is terminal. {@code trace_id}/{@code idempotency_key}/
 * {@code max_attempts}/{@code version} added by V19 — the idempotency key is the
 * duplicate-enqueue guard and {@code @Version} guards concurrent dispatch updates.
 */
@Entity
@Table(name = "webhook_delivery_logs")
public class WebhookDeliveryLogEntity {

  @Id
  private UUID id;

  @Column(name = "webhook_config_id", nullable = false)
  private UUID webhookConfigId;

  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> payload;

  @Column(name = "response_code")
  private Integer responseCode;

  @Column(name = "response_body", columnDefinition = "text")
  private String responseBody;

  @Column(name = "latency_ms")
  private Integer latencyMs;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private WebhookDeliveryStatus status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_retry_at")
  private Instant nextRetryAt;

  @Column(name = "trace_id", length = 64)
  private String traceId;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts = 3;

  @Column(name = "idempotency_key", length = 255)
  private String idempotencyKey;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected WebhookDeliveryLogEntity() {
  }

  public WebhookDeliveryLogEntity(UUID id, UUID webhookConfigId, String eventType,
      Map<String, Object> payload, Integer responseCode, String responseBody, Integer latencyMs,
      WebhookDeliveryStatus status, int attemptCount, Instant nextRetryAt, Instant createdAt,
      Instant updatedAt) {
    this(id, webhookConfigId, eventType, payload, responseCode, responseBody, latencyMs, status,
        attemptCount, nextRetryAt, null, 3, null, createdAt, updatedAt);
  }

  public WebhookDeliveryLogEntity(UUID id, UUID webhookConfigId, String eventType,
      Map<String, Object> payload, Integer responseCode, String responseBody, Integer latencyMs,
      WebhookDeliveryStatus status, int attemptCount, Instant nextRetryAt, String traceId,
      int maxAttempts, String idempotencyKey, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.webhookConfigId = webhookConfigId;
    this.eventType = eventType;
    this.payload = payload;
    this.responseCode = responseCode;
    this.responseBody = responseBody;
    this.latencyMs = latencyMs;
    this.status = status;
    this.attemptCount = attemptCount;
    this.nextRetryAt = nextRetryAt;
    this.traceId = traceId;
    this.maxAttempts = maxAttempts;
    this.idempotencyKey = idempotencyKey;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWebhookConfigId() {
    return webhookConfigId;
  }

  public String getEventType() {
    return eventType;
  }

  public Map<String, Object> getPayload() {
    return payload;
  }

  public Integer getResponseCode() {
    return responseCode;
  }

  public String getResponseBody() {
    return responseBody;
  }

  public Integer getLatencyMs() {
    return latencyMs;
  }

  public WebhookDeliveryStatus getStatus() {
    return status;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public Instant getNextRetryAt() {
    return nextRetryAt;
  }

  public String getTraceId() {
    return traceId;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }

  /** Records one attempt's HTTP outcome and advances the retry bookkeeping (I4). */
  public void recordAttempt(WebhookDeliveryStatus status, Integer responseCode,
      String responseBody, Integer latencyMs, Instant nextRetryAt, Instant updatedAt) {
    this.status = status;
    this.responseCode = responseCode;
    this.responseBody = responseBody;
    this.latencyMs = latencyMs;
    this.attemptCount = this.attemptCount + 1;
    this.nextRetryAt = nextRetryAt;
    this.updatedAt = updatedAt;
  }

  // --- Story 22-1 delivery status machine (NotificationJobEntity mutator precedent) ---

  /** 2xx: terminal success. */
  public void markDelivered(Integer responseCode, String responseBody, Integer latencyMs,
      Instant updatedAt) {
    this.status = WebhookDeliveryStatus.DELIVERED;
    this.responseCode = responseCode;
    this.responseBody = responseBody;
    this.latencyMs = latencyMs;
    this.attemptCount = this.attemptCount + 1;
    this.nextRetryAt = null;
    this.updatedAt = updatedAt;
  }

  /** 4xx: terminal client rejection — a client error never heals on retry. */
  public void markFailed(Integer responseCode, String responseBody, Integer latencyMs,
      Instant updatedAt) {
    this.status = WebhookDeliveryStatus.FAILED;
    this.responseCode = responseCode;
    this.responseBody = responseBody;
    this.latencyMs = latencyMs;
    this.attemptCount = this.attemptCount + 1;
    this.nextRetryAt = null;
    this.updatedAt = updatedAt;
  }

  /** 5xx/timeout with attempts left: schedule the next retry with backoff. */
  public void markRetrying(Integer responseCode, String responseBody, Integer latencyMs,
      Instant nextRetryAt, Instant updatedAt) {
    this.status = WebhookDeliveryStatus.RETRYING;
    this.responseCode = responseCode;
    this.responseBody = responseBody;
    this.latencyMs = latencyMs;
    this.attemptCount = this.attemptCount + 1;
    this.nextRetryAt = nextRetryAt;
    this.updatedAt = updatedAt;
  }

  /** Attempts exhausted: terminal dead-letter state, never swept again. */
  public void markDlq(Integer responseCode, String responseBody, Integer latencyMs,
      Instant updatedAt) {
    this.status = WebhookDeliveryStatus.DLQ;
    this.responseCode = responseCode;
    this.responseBody = responseBody;
    this.latencyMs = latencyMs;
    this.attemptCount = this.attemptCount + 1;
    this.nextRetryAt = null;
    this.updatedAt = updatedAt;
  }

  /**
   * Circuit-open is dependency state, not a delivery failure: the attempt count is
   * intentionally NOT incremented (a subscriber outage must never burn the retry
   * budget) and the row stays due for a later sweep (NotificationJobEntity
   * .markCircuitOpen precedent).
   */
  public void markCircuitOpen(Instant nextRetryAt, Instant updatedAt) {
    this.status = WebhookDeliveryStatus.RETRYING;
    this.nextRetryAt = nextRetryAt;
    this.updatedAt = updatedAt;
  }
}
