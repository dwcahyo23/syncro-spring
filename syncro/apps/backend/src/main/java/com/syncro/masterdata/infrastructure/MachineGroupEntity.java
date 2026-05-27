package com.syncro.masterdata.infrastructure;

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

@Entity
@Table(name = "machine_groups")
public class MachineGroupEntity {
  @Id
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "plant_id", nullable = false)
  private PlantEntity plant;

  @Column(nullable = false)
  private String name;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MachineGroupEntity() {
  }

  public MachineGroupEntity(UUID id, PlantEntity plant, String name, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.plant = plant;
    this.name = name;
    this.createdAt = createdAt;
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void update(PlantEntity plant, String name, Instant updatedAt) {
    this.plant = plant;
    this.name = name;
    this.updatedAt = updatedAt;
  }
}
