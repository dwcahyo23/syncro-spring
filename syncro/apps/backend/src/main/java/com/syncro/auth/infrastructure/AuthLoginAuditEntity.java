package com.syncro.auth.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code auth_login_audits} row (blueprint I2, story 15-2). One row per
 * login attempt — success or failure — for security auditing. Append-only: no
 * mutation methods.
 */
@Entity
@Table(name = "auth_login_audits")
public class AuthLoginAuditEntity {

  @Id
  private UUID id;

  @Column(name = "user_id")
  private UUID userId;

  @Column(nullable = false, length = 255)
  private String identifier;

  @Column(name = "ip_address", length = 64)
  private String ipAddress;

  @Column(name = "user_agent", columnDefinition = "text")
  private String userAgent;

  @Column(name = "was_success", nullable = false)
  private boolean wasSuccess;

  @Column(name = "failure_reason", length = 255)
  private String failureReason;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  protected AuthLoginAuditEntity() {
  }

  public AuthLoginAuditEntity(UUID id, UUID userId, String identifier, String ipAddress,
      String userAgent, boolean wasSuccess, String failureReason, Instant occurredAt) {
    this.id = id;
    this.userId = userId;
    this.identifier = identifier;
    this.ipAddress = ipAddress;
    this.userAgent = userAgent;
    this.wasSuccess = wasSuccess;
    this.failureReason = failureReason;
    this.occurredAt = occurredAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getIdentifier() {
    return identifier;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public boolean wasSuccess() {
    return wasSuccess;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
