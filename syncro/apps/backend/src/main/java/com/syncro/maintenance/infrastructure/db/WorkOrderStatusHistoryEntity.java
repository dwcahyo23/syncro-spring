package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code work_order_status_history} row (AD-4): one row per transition with
 * the acting user (actor, UUID string), the transition source (MANUAL/DERIVED/SYNC)
 * and a trace id for correlation. Grown out of the 10-1 schema-only pass-through into
 * a real persisted aggregate with the create/assign flow.
 */
@Entity
@Table(name = "work_order_status_history")
public class WorkOrderStatusHistoryEntity {

  @Id
  private UUID id;

  @Column(name = "work_order_id", nullable = false, length = 50)
  private String workOrderId;

  @Column(name = "from_status", length = 20)
  private String fromStatus;

  @Column(name = "to_status", nullable = false, length = 20)
  private String toStatus;

  @Column(nullable = false, length = 8)
  private String source;

  @Column(length = 50)
  private String actor;

  @Column(name = "trace_id", length = 36)
  private String traceId;

  @Column(name = "transitioned_at", nullable = false)
  private Instant transitionedAt;

  protected WorkOrderStatusHistoryEntity() {
  }

  public WorkOrderStatusHistoryEntity(UUID id, String workOrderId, String fromStatus, String toStatus,
      String source, String actor, String traceId, Instant transitionedAt) {
    this.id = id;
    this.workOrderId = workOrderId;
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
    this.source = source;
    this.actor = actor;
    this.traceId = traceId;
    this.transitionedAt = transitionedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getWorkOrderId() {
    return workOrderId;
  }

  public String getFromStatus() {
    return fromStatus;
  }

  public String getToStatus() {
    return toStatus;
  }

  public String getSource() {
    return source;
  }

  public String getActor() {
    return actor;
  }

  public String getTraceId() {
    return traceId;
  }

  public Instant getTransitionedAt() {
    return transitionedAt;
  }
}
