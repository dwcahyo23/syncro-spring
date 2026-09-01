package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.WorkAssignmentParentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code work_assignments} row (blueprint B3, AD-17, story 15-2). The real
 * executor record of a workorder — {@code work_orders.assigned_technician_id} stays
 * the lead-technician display column. {@code work_order_id} is the VARCHAR(50)
 * work_orders PK as a plain reference column (AD-3/AD-4, no JPA association).
 */
@Entity
@Table(name = "work_assignments")
public class WorkAssignmentEntity {

  @Id
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "parent_type", nullable = false, length = 24)
  private WorkAssignmentParentType parentType;

  @Column(name = "work_order_id", nullable = false, length = 50)
  private String workOrderId;

  @Column(name = "technician_id", nullable = false)
  private UUID technicianId;

  @Column(name = "assigned_by")
  private UUID assignedBy;

  @Column(name = "assigned_at", nullable = false)
  private Instant assignedAt;

  @Column(name = "dropped_at")
  private Instant droppedAt;

  @Column(name = "dropped_by")
  private UUID droppedBy;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WorkAssignmentEntity() {
  }

  public WorkAssignmentEntity(UUID id, WorkAssignmentParentType parentType, String workOrderId,
      UUID technicianId, UUID assignedBy, Instant assignedAt, Instant droppedAt, UUID droppedBy,
      boolean active, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.parentType = parentType;
    this.workOrderId = workOrderId;
    this.technicianId = technicianId;
    this.assignedBy = assignedBy;
    this.assignedAt = assignedAt;
    this.droppedAt = droppedAt;
    this.droppedBy = droppedBy;
    this.active = active;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public WorkAssignmentParentType getParentType() {
    return parentType;
  }

  public String getWorkOrderId() {
    return workOrderId;
  }

  public UUID getTechnicianId() {
    return technicianId;
  }

  public UUID getAssignedBy() {
    return assignedBy;
  }

  public Instant getAssignedAt() {
    return assignedAt;
  }

  public Instant getDroppedAt() {
    return droppedAt;
  }

  public UUID getDroppedBy() {
    return droppedBy;
  }

  public boolean isActive() {
    return active;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Deactivation semantics (AD-17 drop): records who dropped and when. */
  public void drop(UUID droppedBy, Instant droppedAt, Instant updatedAt) {
    this.droppedBy = droppedBy;
    this.droppedAt = droppedAt;
    this.active = false;
    this.updatedAt = updatedAt;
  }
}
