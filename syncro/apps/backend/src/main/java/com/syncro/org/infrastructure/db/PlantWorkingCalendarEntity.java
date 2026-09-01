package com.syncro.org.infrastructure.db;

import com.syncro.org.domain.WorkweekMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code plant_working_calendars} row (blueprint A12, story 15-2). One
 * calendar per {@code (plant_id, year)}; {@code date}-level exceptions live in
 * {@link PlantWorkingCalendarDateEntity}. Year range 2000–2999 enforced by the V1
 * CHECK — never re-asserted in Java.
 */
@Entity
@Table(name = "plant_working_calendars")
public class PlantWorkingCalendarEntity {

  @Id
  private UUID id;

  @Column(name = "plant_id", nullable = false)
  private UUID plantId;

  @Column(nullable = false)
  private int year;

  @Enumerated(EnumType.STRING)
  @Column(name = "workweek_mode", nullable = false, length = 12)
  private WorkweekMode workweekMode;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PlantWorkingCalendarEntity() {
  }

  public PlantWorkingCalendarEntity(UUID id, UUID plantId, int year, WorkweekMode workweekMode,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.plantId = plantId;
    this.year = year;
    this.workweekMode = workweekMode;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPlantId() {
    return plantId;
  }

  public int getYear() {
    return year;
  }

  public WorkweekMode getWorkweekMode() {
    return workweekMode;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
