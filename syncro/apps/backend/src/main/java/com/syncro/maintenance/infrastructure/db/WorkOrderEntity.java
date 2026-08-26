package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code work_orders} row (AD-3/AD-4). The dual-source id is the VARCHAR PK
 * (external sheet_no for SYNCED, WO-YYMM-XXXXX for INTERNAL); machine/category/created_by
 * are plain UUID columns — no cross-aggregate JPA associations. Grown out of the 10-1
 * schema-only pass-through into a real persisted aggregate with the create/assign flow.
 */
@Entity
@Table(name = "work_orders")
public class WorkOrderEntity {

  @Id
  @Column(length = 50)
  private String id;

  @Column(nullable = false, length = 8)
  private String source;

  @Column(name = "parent_id", length = 50)
  private String parentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private WorkOrderStatus status;

  @Column(name = "category_id")
  private UUID categoryId;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "sync_version", nullable = false)
  private long syncVersion;

  @Column(name = "idempotency_key", length = 64)
  private String idempotencyKey;

  @Column(name = "assigned_technician_id")
  private UUID assignedTechnicianId;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WorkOrderEntity() {
  }

  public WorkOrderEntity(String id, String source, String parentId, WorkOrderStatus status, UUID categoryId,
      UUID machineId, String description, long syncVersion, String idempotencyKey, UUID assignedTechnicianId,
      UUID createdBy, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.source = source;
    this.parentId = parentId;
    this.status = status;
    this.categoryId = categoryId;
    this.machineId = machineId;
    this.description = description;
    this.syncVersion = syncVersion;
    this.idempotencyKey = idempotencyKey;
    this.assignedTechnicianId = assignedTechnicianId;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getId() {
    return id;
  }

  public String getSource() {
    return source;
  }

  public String getParentId() {
    return parentId;
  }

  public WorkOrderStatus getStatus() {
    return status;
  }

  public UUID getCategoryId() {
    return categoryId;
  }

  public UUID getMachineId() {
    return machineId;
  }

  public String getDescription() {
    return description;
  }

  public long getSyncVersion() {
    return syncVersion;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public UUID getAssignedTechnicianId() {
    return assignedTechnicianId;
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

  /** OPEN → ASSIGNED (FR-113): records the executing technician and bumps the timestamp. */
  public void assign(UUID assignedTechnicianId, Instant updatedAt) {
    this.assignedTechnicianId = assignedTechnicianId;
    this.status = WorkOrderStatus.ASSIGNED;
    this.updatedAt = updatedAt;
  }
}
