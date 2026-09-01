package com.syncro.org.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code role_permission_mappings} row (blueprint A6, story 15-2). Grants
 * (or explicitly denies with {@code is_granted=false}) a menu feature to a system
 * role, optionally scoped to one domain context. Uniqueness is
 * {@code (system_role_id, menu_feature_id, domain_id)} with NULLs-distinct semantics
 * handled by the V1 COALESCE expression index — never asserted in Java.
 */
@Entity
@Table(name = "role_permission_mappings")
public class RolePermissionMappingEntity {

  @Id
  private UUID id;

  @Column(name = "system_role_id", nullable = false)
  private UUID systemRoleId;

  @Column(name = "menu_feature_id", nullable = false)
  private UUID menuFeatureId;

  @Column(name = "domain_id")
  private UUID domainId;

  @Column(name = "is_granted", nullable = false)
  private boolean granted = true;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected RolePermissionMappingEntity() {
  }

  public RolePermissionMappingEntity(UUID id, UUID systemRoleId, UUID menuFeatureId, UUID domainId,
      boolean granted, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.systemRoleId = systemRoleId;
    this.menuFeatureId = menuFeatureId;
    this.domainId = domainId;
    this.granted = granted;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSystemRoleId() {
    return systemRoleId;
  }

  public UUID getMenuFeatureId() {
    return menuFeatureId;
  }

  public UUID getDomainId() {
    return domainId;
  }

  public boolean isGranted() {
    return granted;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
