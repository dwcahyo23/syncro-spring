package com.syncro.org.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code menu_features} row (blueprint A7, story 15-2). One feature toggle
 * per {@code module:action} code (e.g. {@code cmms:wo:read}) — the subject of
 * {@link RolePermissionMappingEntity} grants.
 */
@Entity
@Table(name = "menu_features")
public class MenuFeatureEntity {

  @Id
  private UUID id;

  @Column(nullable = false, length = 100)
  private String code;

  @Column(nullable = false, length = 50)
  private String module;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MenuFeatureEntity() {
  }

  public MenuFeatureEntity(UUID id, String code, String module, String name, boolean active,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.code = code;
    this.module = module;
    this.name = name;
    this.active = active;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getModule() {
    return module;
  }

  public String getName() {
    return name;
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
}
