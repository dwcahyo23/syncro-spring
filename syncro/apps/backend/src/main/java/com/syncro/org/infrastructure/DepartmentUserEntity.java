package com.syncro.org.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Membership link between a department and a user (technician subordinate).
 * Renamed from {@code department_members} to {@code department_users} by the ORM
 * target blueprint (A3, story 15-1). Unique {@code (department_id, user_id)}
 * allows multi-department membership. Deleting the department cascades; deleting
 * the user cascades.
 */
@Entity
@Table(name = "department_users")
public class DepartmentUserEntity {

  @Id
  private UUID id;

  @Column(name = "department_id", nullable = false)
  private UUID departmentId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "assigned_by", nullable = false)
  private UUID assignedBy;

  @Column(name = "assigned_at", nullable = false)
  private Instant assignedAt;

  protected DepartmentUserEntity() {
  }

  public DepartmentUserEntity(UUID id, UUID departmentId, UUID userId, UUID assignedBy, Instant assignedAt) {
    this.id = id;
    this.departmentId = departmentId;
    this.userId = userId;
    this.assignedBy = assignedBy;
    this.assignedAt = assignedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getDepartmentId() {
    return departmentId;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getAssignedBy() {
    return assignedBy;
  }

  public Instant getAssignedAt() {
    return assignedAt;
  }
}
