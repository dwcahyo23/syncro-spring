package com.syncro.notification.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "waha_templates")
public class WahaTemplateEntity {

  @Id
  private UUID id;

  @Column(name = "template_key", nullable = false, length = 64, unique = true)
  private String templateKey;

  @Column(name = "body", nullable = false, columnDefinition = "TEXT")
  private String body;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WahaTemplateEntity() {
  }

  public WahaTemplateEntity(UUID id, String templateKey, String body, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.templateKey = templateKey;
    this.body = body;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getTemplateKey() {
    return templateKey;
  }

  public String getBody() {
    return body;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void updateBody(String newBody, Instant now) {
    this.body = newBody;
    this.updatedAt = now;
  }
}
