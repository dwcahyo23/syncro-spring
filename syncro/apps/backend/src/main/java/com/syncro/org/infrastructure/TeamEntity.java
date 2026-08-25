package com.syncro.org.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A cross-plant team (AD-13): an expiry-dated group of members and target machines.
 * Expiry is evaluated lazily at scope-derive time via the injected {@code Clock} —
 * no scheduled job and no stored {@code active} column. {@link #getExpiresAt()} is
 * mutable (extend or shorten, but must remain strictly in the future at mutation time).
 */
@Entity
@Table(name = "teams")
public class TeamEntity {

  @Id
  private UUID id;

  @Column(nullable = false)
  private String name;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Version
  @Column(nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected TeamEntity() {
  }

  public TeamEntity(UUID id, String name, Instant expiresAt, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.name = name;
    this.expiresAt = expiresAt;
    this.version = 0;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void update(String name, Instant expiresAt, Instant updatedAt) {
    this.name = name;
    this.expiresAt = expiresAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
