package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted workorder_acks row (story 14-4, FR-181). One row per acknowledged workorder.
 * The UNIQUE(work_order_id) constraint ensures at most one ack per workorder.
 */
@Entity
@Table(name = "workorder_acks")
public class WorkOrderAckEntity {

  @Id
  private UUID id;

  @Column(name = "work_order_id", nullable = false, length = 50)
  private String workOrderId;

  @Column(name = "acknowledged_by", nullable = false)
  private UUID acknowledgedBy;

  @Column(name = "acknowledged_at", nullable = false)
  private Instant acknowledgedAt;

  @Column(name = "trace_id", length = 64)
  private String traceId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WorkOrderAckEntity() {
  }

  public WorkOrderAckEntity(UUID id, String workOrderId, UUID acknowledgedBy, Instant acknowledgedAt, String traceId) {
    this.id = id;
    this.workOrderId = workOrderId;
    this.acknowledgedBy = acknowledgedBy;
    this.acknowledgedAt = acknowledgedAt;
    this.traceId = traceId;
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

  public UUID getId() { return id; }
  public String getWorkOrderId() { return workOrderId; }
  public UUID getAcknowledgedBy() { return acknowledgedBy; }
  public Instant getAcknowledgedAt() { return acknowledgedAt; }
  public String getTraceId() { return traceId; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}