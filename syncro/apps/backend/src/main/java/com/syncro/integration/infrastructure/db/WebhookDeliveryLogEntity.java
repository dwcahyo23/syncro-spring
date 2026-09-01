package com.syncro.integration.infrastructure.db;

import com.syncro.integration.domain.WebhookDeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted {@code webhook_delivery_logs} row (blueprint I4, story 15-2). One delivery
 * attempt stream per webhook config: payload JSONB, HTTP response, latency, retry
 * bookkeeping. {@code next_retry_at} backs the retry sweep; DLQ is terminal.
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

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WebhookDeliveryLogEntity() {
  }

  public WebhookDeliveryLogEntity(UUID id, UUID webhookConfigId, String eventType,
      Map<String, Object> payload, Integer responseCode, String responseBody, Integer latencyMs,
      WebhookDeliveryStatus status, int attemptCount, Instant nextRetryAt, Instant createdAt,
      Instant updatedAt) {
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
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
}
