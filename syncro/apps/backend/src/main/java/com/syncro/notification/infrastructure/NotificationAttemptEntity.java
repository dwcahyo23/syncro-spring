package com.syncro.notification.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_attempts")
public class NotificationAttemptEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "job_id", nullable = false)
  private UUID jobId;

  @Column(name = "attempt_number", nullable = false)
  private int attemptNumber;

  @Column(name = "status", nullable = false, length = 16)
  private String status;

  @Column(name = "attempted_at", nullable = false)
  private Instant attemptedAt;

  @Column(name = "response_detail", length = 512)
  private String responseDetail;

  @Column(name = "trace_id", length = 64)
  private String traceId;

  protected NotificationAttemptEntity() {
  }

  public NotificationAttemptEntity(UUID jobId, int attemptNumber, String status,
      String responseDetail, String traceId) {
    this.jobId = jobId;
    this.attemptNumber = attemptNumber;
    this.status = status;
    this.responseDetail = responseDetail;
    this.traceId = traceId;
  }

  @PrePersist
  void prePersist() {
    if (this.attemptedAt == null) {
      this.attemptedAt = Instant.now();
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getJobId() {
    return jobId;
  }

  public int getAttemptNumber() {
    return attemptNumber;
  }

  public String getStatus() {
    return status;
  }

  public Instant getAttemptedAt() {
    return attemptedAt;
  }

  public String getResponseDetail() {
    return responseDetail;
  }

  public String getTraceId() {
    return traceId;
  }
}
