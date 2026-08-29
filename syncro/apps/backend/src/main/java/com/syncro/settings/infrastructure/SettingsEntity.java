package com.syncro.settings.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Persisted {@code settings} row (story 14-3, FR-175). Single-row table with a CHECK
 * constraint enforcing {@code singleton_key = 1}, so there is always exactly one row.
 * Stores the current company logo Garage object key — bytes live in Garage under
 * {@code settings/logo/{uuid}.{ext}}.
 */
@Entity
@Table(name = "settings")
public class SettingsEntity {

  @Id
  @Column(name = "singleton_key", nullable = false)
  private short singletonKey;

  @Column(name = "logo_object_key", length = 512)
  private String logoObjectKey;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SettingsEntity() {
  }

  public SettingsEntity(short singletonKey, String logoObjectKey, Instant createdAt, Instant updatedAt) {
    this.singletonKey = singletonKey;
    this.logoObjectKey = logoObjectKey;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public short getSingletonKey() {
    return singletonKey;
  }

  public String getLogoObjectKey() {
    return logoObjectKey;
  }

  public void setLogoObjectKey(String logoObjectKey, Instant updatedAt) {
    this.logoObjectKey = logoObjectKey;
    this.updatedAt = updatedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}