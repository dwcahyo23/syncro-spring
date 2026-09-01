package com.syncro.org.infrastructure;

import com.syncro.org.domain.JobBindingScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Job title master (user-master reference data, blueprint A4). */
@Entity
@Table(name = "job_titles")
public class JobTitleEntity {

  @Id
  private UUID id;

  @Column(nullable = false, length = 50)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(length = 1000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "binding_scope", nullable = false, length = 16)
  private JobBindingScope bindingScope = JobBindingScope.NONE;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(name = "default_system_role_id")
  private UUID defaultSystemRoleId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected JobTitleEntity() {
  }

  public JobTitleEntity(UUID id, String code, String name, String description, Instant createdAt, Instant updatedAt) {
    this(id, code, name, description, JobBindingScope.NONE, true, null, createdAt, updatedAt);
  }

  public JobTitleEntity(UUID id, String code, String name, String description, JobBindingScope bindingScope,
      boolean active, UUID defaultSystemRoleId, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.code = code;
    this.name = name;
    this.description = description;
    this.bindingScope = bindingScope;
    this.active = active;
    this.defaultSystemRoleId = defaultSystemRoleId;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void update(String code, String name, String description, JobBindingScope bindingScope,
      UUID defaultSystemRoleId, Boolean active, Instant updatedAt) {
    this.code = code;
    this.name = name;
    this.description = description;
    if (bindingScope != null) {
      this.bindingScope = bindingScope;
    }
    this.defaultSystemRoleId = defaultSystemRoleId;
    if (active != null) {
      this.active = active;
    }
    this.updatedAt = updatedAt;
  }

  public void deactivate(Instant updatedAt) {
    this.active = false;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public JobBindingScope getBindingScope() {
    return bindingScope;
  }

  public boolean isActive() {
    return active;
  }

  public UUID getDefaultSystemRoleId() {
    return defaultSystemRoleId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
