package com.syncro.org.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code domain_contexts} row (blueprint A8, story 15-2). Bounded-context
 * label (maintenance/production/inventory) that scopes a role permission mapping;
 * {@code null} domain on the mapping means "all domains".
 */
@Entity
@Table(name = "domain_contexts")
public class DomainContextEntity {

  @Id
  private UUID id;

  @Column(nullable = false, length = 64)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected DomainContextEntity() {
  }

  public DomainContextEntity(UUID id, String code, String name, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.code = code;
    this.name = name;
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
