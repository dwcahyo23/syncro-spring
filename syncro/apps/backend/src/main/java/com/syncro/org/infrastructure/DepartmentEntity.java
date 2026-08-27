package com.syncro.org.infrastructure;

import com.syncro.auth.infrastructure.PlantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Organization-maintenance department (people unit, per plant). SPV/MG leaders are
 * stored as plain user-id columns (nullable); membership lives in
 * {@code department_members}. Soft-inactive only — never hard-deleted.
 */
@Entity
@Table(name = "departments")
public class DepartmentEntity {

  @Id
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "plant_id", nullable = false)
  private PlantEntity plant;

  @Column(nullable = false)
  private String name;

  @Column(name = "spv_id")
  private UUID spvId;

  @Column(name = "mg_id")
  private UUID mgId;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected DepartmentEntity() {
  }

  public DepartmentEntity(UUID id, PlantEntity plant, String name, UUID spvId, UUID mgId, boolean active,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.plant = plant;
    this.name = name;
    this.spvId = spvId;
    this.mgId = mgId;
    this.active = active;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void update(String name, UUID spvId, UUID mgId, Boolean active, Instant updatedAt) {
    this.name = name;
    this.spvId = spvId;
    this.mgId = mgId;
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

  public PlantEntity getPlant() {
    return plant;
  }

  public String getName() {
    return name;
  }

  public UUID getSpvId() {
    return spvId;
  }

  public UUID getMgId() {
    return mgId;
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
