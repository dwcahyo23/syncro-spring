package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Schema-only JPA pass-through for the {@code work_order_status_history} table
 * (story 10-1). Status transitions are written by the 10.3 state machine; this
 * entity only keeps ddl-auto=validate aligned with the V47 DDL.
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
}
