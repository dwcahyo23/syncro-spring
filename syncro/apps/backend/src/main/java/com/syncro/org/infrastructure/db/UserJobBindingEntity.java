package com.syncro.org.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code user_job_bindings} row (blueprint A9, story 15-2). Binds a user to
 * their single job title ({@code uq_user_job_bindings_user} — one user, one title);
 * the superseded per-user {@code job_title_id} column on {@code auth_users} stays
 * until the org epic migrates the read path.
 */
@Entity
@Table(name = "user_job_bindings")
public class UserJobBindingEntity {

  @Id
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "job_title_id", nullable = false)
  private UUID jobTitleId;

  @Column(name = "assigned_by", nullable = false)
  private UUID assignedBy;

  @Column(name = "assigned_at", nullable = false)
  private Instant assignedAt;

  protected UserJobBindingEntity() {
  }

  public UserJobBindingEntity(UUID id, UUID userId, UUID jobTitleId, UUID assignedBy,
      Instant assignedAt) {
    this.id = id;
    this.userId = userId;
    this.jobTitleId = jobTitleId;
    this.assignedBy = assignedBy;
    this.assignedAt = assignedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getJobTitleId() {
    return jobTitleId;
  }

  public UUID getAssignedBy() {
    return assignedBy;
  }

  public Instant getAssignedAt() {
    return assignedAt;
  }
}
