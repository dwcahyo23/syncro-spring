package com.syncro.machine.infrastructure;

import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "machines")
public class MachineEntity {
  @Id
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "plant_id", nullable = false)
  private PlantEntity plant;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "machine_group_id", nullable = false)
  private MachineGroupEntity machineGroup;

  @Column(nullable = false, length = 64)
  private String code;

  @Column(length = 255)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private MachineStatus status;

  @Column(length = 255)
  private String brand;

  @Column(name = "installed_at")
  private LocalDate installedAt;

  @Column(length = 1000)
  private String notes;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "optional_telemetry_fields", columnDefinition = "jsonb")
  private List<String> optionalTelemetryFields;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MachineEntity() {
  }

  public MachineEntity(UUID id, PlantEntity plant, MachineGroupEntity machineGroup, String code, String name,
      MachineStatus status, String brand, LocalDate installedAt, String notes, List<String> optionalTelemetryFields,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.plant = plant;
    this.machineGroup = machineGroup;
    this.code = code;
    this.name = name;
    this.status = status;
    this.brand = brand;
    this.installedAt = installedAt;
    this.notes = notes;
    this.optionalTelemetryFields = optionalTelemetryFields;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public PlantEntity getPlant() {
    return plant;
  }

  public MachineGroupEntity getMachineGroup() {
    return machineGroup;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public MachineStatus getStatus() {
    return status;
  }

  public String getBrand() {
    return brand;
  }

  public LocalDate getInstalledAt() {
    return installedAt;
  }

  public String getNotes() {
    return notes;
  }

  public List<String> getOptionalTelemetryFields() {
    return optionalTelemetryFields;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void update(MachineGroupEntity machineGroup, String code, String name, MachineStatus status, String brand,
      LocalDate installedAt, String notes, List<String> optionalTelemetryFields, Instant updatedAt) {
    this.machineGroup = machineGroup;
    this.code = code;
    this.name = name;
    this.status = status;
    this.brand = brand;
    this.installedAt = installedAt;
    this.notes = notes;
    this.optionalTelemetryFields = optionalTelemetryFields;
    this.updatedAt = updatedAt;
  }
}
