package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.TodoStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code workorder_todos} row (story 10-7, FR-119). Each todo is a local
 * operational field on a workorder — never touches status or sync_version. The FK
 * has ON DELETE CASCADE so removing a workorder also removes its todos.
 */
@Entity
@Table(name = "workorder_todos")
public class WorkOrderTodoEntity {

  @Id
  private UUID id;

  @Column(name = "work_order_id", nullable = false, length = 50)
  private String workorderId;

  @Column(nullable = false, length = 200)
  private String title;

  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "assigned_technician_id")
  private UUID assignedTechnicianId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private TodoStatus status;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected WorkOrderTodoEntity() {
  }

  public WorkOrderTodoEntity(UUID id, String workorderId, String title, String description,
      UUID assignedTechnicianId, TodoStatus status, int sortOrder, UUID createdBy,
      Instant createdAt, Instant updatedAt, Instant completedAt) {
    this.id = id;
    this.workorderId = workorderId;
    this.title = title;
    this.description = description;
    this.assignedTechnicianId = assignedTechnicianId;
    this.status = status;
    this.sortOrder = sortOrder;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.completedAt = completedAt;
  }

  public void assign(UUID assignedTechnicianId, Instant updatedAt) {
    this.assignedTechnicianId = assignedTechnicianId;
    this.updatedAt = updatedAt;
  }

  public void complete(Instant now) {
    this.status = TodoStatus.COMPLETED;
    this.completedAt = now;
    this.updatedAt = now;
  }

  public void cancel(Instant now) {
    this.status = TodoStatus.CANCELLED;
    this.updatedAt = now;
  }

  public void reorder(int sortOrder, Instant updatedAt) {
    this.sortOrder = sortOrder;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getWorkorderId() {
    return workorderId;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public UUID getAssignedTechnicianId() {
    return assignedTechnicianId;
  }

  public TodoStatus getStatus() {
    return status;
  }

  public int getSortOrder() {
    return sortOrder;
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

  public Instant getCompletedAt() {
    return completedAt;
  }
}