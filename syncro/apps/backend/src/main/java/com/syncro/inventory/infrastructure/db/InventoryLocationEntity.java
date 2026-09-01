package com.syncro.inventory.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code inventory_locations} row (blueprint E1, story 15-1). Unique
 * {@code (plant_id, code)} per the blueprint; the seed creates one default
 * location per plant ("GUDANG UTAMA", code GUDANG-UTAMA).
 */
@Entity
@Table(name = "inventory_locations")
public class InventoryLocationEntity {

  @Id
  private UUID id;

  @Column(name = "plant_id", nullable = false)
  private UUID plantId;

  @Column(nullable = false, length = 64)
  private String code;

  @Column(nullable = false, length = 255)
  private String name;

  @Column(length = 1000)
  private String description;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected InventoryLocationEntity() {
  }

  public InventoryLocationEntity(UUID id, UUID plantId, String code, String name,
      String description, boolean active, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.plantId = plantId;
    this.code = code;
    this.name = name;
    this.description = description;
    this.active = active;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  /**
   * Full PUT replacement of the editable columns (story 18-2). Encapsulated so the
   * lifecycle stays in one place: {@code id}, {@code plantId} and {@code createdAt}
   * are immutable, and there is no generic setter for {@code active}. Deactivation is
   * an {@code active=false} update, not a separate transition.
   */
  public void update(String code, String name, String description, boolean active,
      Instant updatedAt) {
    this.code = code;
    this.name = name;
    this.description = description;
    this.active = active;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPlantId() {
    return plantId;
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
