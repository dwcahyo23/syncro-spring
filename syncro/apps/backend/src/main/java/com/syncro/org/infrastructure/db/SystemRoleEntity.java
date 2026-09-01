package com.syncro.org.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code system_roles} row (blueprint A5, story 15-2). Code is the stable
 * external identifier (unique); {@code level} orders roles for hierarchy display and
 * is enforced non-negative by the V1 CHECK.
 */
@Entity
@Table(name = "system_roles")
public class SystemRoleEntity {

  @Id
  private UUID id;

  @Column(nullable = false, length = 64)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(nullable = false)
  private int level;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SystemRoleEntity() {
  }

  public SystemRoleEntity(UUID id, String code, String name, int level, boolean active,
      String description, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.code = code;
    this.name = name;
    this.level = level;
    this.active = active;
    this.description = description;
    this.createdAt = createdAt;
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

  public int getLevel() {
    return level;
  }

  public boolean isActive() {
    return active;
  }

  public String getDescription() {
    return description;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
