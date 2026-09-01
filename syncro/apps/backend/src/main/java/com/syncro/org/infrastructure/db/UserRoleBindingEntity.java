package com.syncro.org.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code user_role_bindings} row (blueprint A10, story 15-2). Binds a user
 * to a system role, optionally flagged as an explicit override of the job-title
 * default. Uniqueness {@code (user_id, system_role_id)} via V1 constraint.
 */
@Entity
@Table(name = "user_role_bindings")
public class UserRoleBindingEntity {

  @Id
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "system_role_id", nullable = false)
  private UUID systemRoleId;

  @Column(name = "is_override", nullable = false)
  private boolean override;

  @Column(name = "assigned_by", nullable = false)
  private UUID assignedBy;

  @Column(name = "assigned_at", nullable = false)
  private Instant assignedAt;

  protected UserRoleBindingEntity() {
  }

  public UserRoleBindingEntity(UUID id, UUID userId, UUID systemRoleId, boolean override,
      UUID assignedBy, Instant assignedAt) {
    this.id = id;
    this.userId = userId;
    this.systemRoleId = systemRoleId;
    this.override = override;
    this.assignedBy = assignedBy;
    this.assignedAt = assignedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getSystemRoleId() {
    return systemRoleId;
  }

  public boolean isOverride() {
    return override;
  }

  public UUID getAssignedBy() {
    return assignedBy;
  }

  public Instant getAssignedAt() {
    return assignedAt;
  }
}
