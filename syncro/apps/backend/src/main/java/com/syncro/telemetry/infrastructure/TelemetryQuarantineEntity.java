package com.syncro.telemetry.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "telemetry_quarantine")
public class TelemetryQuarantineEntity {

  @Id
  private UUID id;

  @Column(name = "trace_id", nullable = false, length = 255)
  private String traceId;

  @Column(name = "topic", nullable = false, columnDefinition = "TEXT")
  private String topic;

  @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
  private String rawPayload;

  @Column(name = "rejection_reason", nullable = false, length = 100)
  private String rejectionReason;

  @Column(name = "rejection_field", length = 100)
  private String rejectionField;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public String getTopic() {
    return topic;
  }

  public void setTopic(String topic) {
    this.topic = topic;
  }

  public String getRawPayload() {
    return rawPayload;
  }

  public void setRawPayload(String rawPayload) {
    this.rawPayload = rawPayload;
  }

  public String getRejectionReason() {
    return rejectionReason;
  }

  public void setRejectionReason(String rejectionReason) {
    this.rejectionReason = rejectionReason;
  }

  public String getRejectionField() {
    return rejectionField;
  }

  public void setRejectionField(String rejectionField) {
    this.rejectionField = rejectionField;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public void setReceivedAt(Instant receivedAt) {
    this.receivedAt = receivedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
