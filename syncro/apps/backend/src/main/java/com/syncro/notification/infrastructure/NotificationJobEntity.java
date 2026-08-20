package com.syncro.notification.infrastructure;

import com.syncro.notification.domain.NotificationJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_jobs")
public class NotificationJobEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "alert_id", nullable = false)
  private UUID alertId;

  @Column(name = "escalation_level", nullable = false, length = 16)
  private String escalationLevel;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private NotificationJobStatus status;

  @Column(name = "recipient_user_id")
  private UUID recipientUserId;

  @Column(name = "recipient_phone", length = 32)
  private String recipientPhone;

  @Column(name = "idempotency_key", nullable = false, length = 128)
  private String idempotencyKey;

  @Column(name = "trace_id", length = 64)
  private String traceId;

  @Column(name = "error_detail", length = 512)
  private String errorDetail;

  @Column(name = "sent_at")
  private Instant sentAt;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount = 0;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts = 3;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected NotificationJobEntity() {
  }

  public NotificationJobEntity(UUID alertId, String escalationLevel, NotificationJobStatus status,
      UUID recipientUserId, String recipientPhone, String idempotencyKey, String traceId,
      String errorDetail) {
    this.alertId = alertId;
    this.escalationLevel = escalationLevel;
    this.status = status;
    this.recipientUserId = recipientUserId;
    this.recipientPhone = recipientPhone;
    this.idempotencyKey = idempotencyKey;
    this.traceId = traceId;
    this.errorDetail = errorDetail;
  }

  public void markSent(Instant now) {
    this.status = NotificationJobStatus.SENT;
    this.sentAt = now;
    this.updatedAt = now;
  }

  public void markExhausted(Instant now) {
    this.status = NotificationJobStatus.EXHAUSTED;
    this.nextAttemptAt = null;
    this.updatedAt = now;
  }

  public void markEscalated(Instant now) {
    this.status = NotificationJobStatus.ESCALATED;
    this.updatedAt = now;
  }

  public void markAttemptFailed(Instant now, Instant nextAttemptAt) {
    this.attemptCount++;
    this.updatedAt = now;
    if (this.attemptCount >= this.maxAttempts) {
      this.status = NotificationJobStatus.EXHAUSTED;
      this.nextAttemptAt = null;
    } else {
      this.status = NotificationJobStatus.PENDING;
      this.nextAttemptAt = nextAttemptAt;
    }
  }

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getAlertId() {
    return alertId;
  }

  public String getEscalationLevel() {
    return escalationLevel;
  }

  public NotificationJobStatus getStatus() {
    return status;
  }

  public UUID getRecipientUserId() {
    return recipientUserId;
  }

  public String getRecipientPhone() {
    return recipientPhone;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getTraceId() {
    return traceId;
  }

  public String getErrorDetail() {
    return errorDetail;
  }

  public Instant getSentAt() {
    return sentAt;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public int getMaxAttempts() {
    return maxAttempts;
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
}
