package com.syncro.org.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted {@code plant_working_calendar_dates} row (blueprint A12, story 15-2).
 * One date-level exception (holiday/off-day/re-scheduled working day) per calendar;
 * unique {@code (working_calendar_id, date)} via V1 constraint.
 */
@Entity
@Table(name = "plant_working_calendar_dates")
public class PlantWorkingCalendarDateEntity {

  @Id
  private UUID id;

  @Column(name = "working_calendar_id", nullable = false)
  private UUID workingCalendarId;

  @Column(nullable = false)
  private LocalDate date;

  @Column(length = 255)
  private String reason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PlantWorkingCalendarDateEntity() {
  }

  public PlantWorkingCalendarDateEntity(UUID id, UUID workingCalendarId, LocalDate date,
      String reason, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.workingCalendarId = workingCalendarId;
    this.date = date;
    this.reason = reason;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkingCalendarId() {
    return workingCalendarId;
  }

  public LocalDate getDate() {
    return date;
  }

  public String getReason() {
    return reason;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
