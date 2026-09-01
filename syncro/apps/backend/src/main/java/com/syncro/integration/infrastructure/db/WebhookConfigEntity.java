package com.syncro.integration.infrastructure.db;

import com.syncro.integration.domain.WebhookDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted {@code webhook_configs} row (blueprint I4, story 15-2). One webhook
 * registration: direction (INBOUND/OUTBOUND), subscribed {@code event_types} JSONB
 * array, and the HMAC secret. The secret is sensitive — callers must mask it in any
 * log or API projection (project-context logging rules).
 */
@Entity
@Table(name = "webhook_configs")
public class WebhookConfigEntity {

  @Id
  private UUID id;

  @Column(nullable = false, length = 200)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private WebhookDirection direction;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "event_types", columnDefinition = "jsonb")
  private List<String> eventTypes;

  @Column(name = "endpoint_url", columnDefinition = "text")
  private String endpointUrl;

  @Column(name = "hmac_secret", length = 512)
  private String hmacSecret;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WebhookConfigEntity() {
  }

  public WebhookConfigEntity(UUID id, String name, WebhookDirection direction,
      List<String> eventTypes, String endpointUrl, String hmacSecret, boolean active,
      UUID createdBy, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.name = name;
    this.direction = direction;
    this.eventTypes = eventTypes;
    this.endpointUrl = endpointUrl;
    this.hmacSecret = hmacSecret;
    this.active = active;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public WebhookDirection getDirection() {
    return direction;
  }

  public List<String> getEventTypes() {
    return eventTypes;
  }

  public String getEndpointUrl() {
    return endpointUrl;
  }

  public String getHmacSecret() {
    return hmacSecret;
  }

  public boolean isActive() {
    return active;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
