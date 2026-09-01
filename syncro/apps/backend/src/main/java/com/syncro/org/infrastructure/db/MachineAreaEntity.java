package com.syncro.org.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code machine_areas} row (blueprint A11, story 15-2). Physical
 * location/area of a machine inside a plant — distinct from {@code machine_groups},
 * which stays the category container (DP1). {@code machines.area_id} references this
 * table; the reference is a plain UUID column on the machine side (AD-3).
 */
@Entity
@Table(name = "machine_areas")
public class MachineAreaEntity {

  @Id
  private UUID id;

  @Column(name = "plant_id", nullable = false)
  private UUID plantId;

  @Column(length = 64)
  private String code;

  @Column(nullable = false, length = 255)
  private String name;

  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MachineAreaEntity() {
  }

  public MachineAreaEntity(UUID id, UUID plantId, String code, String name, String description,
      boolean active, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.plantId = plantId;
    this.code = code;
    this.name = name;
    this.description = description;
    this.active = active;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void update(String code, String name, String description, Boolean active, Instant updatedAt) {
    this.code = code;
    this.name = name;
    this.description = description;
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
