package com.syncro.maintenance.preventive.infrastructure.db;

import com.syncro.maintenance.preventive.domain.PmScheduleDateStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted {@code pm_schedule_dates} row (blueprint F5, story 15-2). One planned
 * execution date of a PM schedule; unique {@code (schedule_id, planned_date)} via
 * V1 constraint. Status moves SCHEDULED → EXECUTED/MISSED/RESCHEDULED.
 */
@Entity
@Table(name = "pm_schedule_dates")
public class PmScheduleDateEntity {

  @Id
  private UUID id;

  @Column(name = "schedule_id", nullable = false)
  private UUID scheduleId;

  @Column(name = "planned_date", nullable = false)
  private LocalDate plannedDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private PmScheduleDateStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PmScheduleDateEntity() {
  }

  public PmScheduleDateEntity(UUID id, UUID scheduleId, LocalDate plannedDate,
      PmScheduleDateStatus status, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.scheduleId = scheduleId;
    this.plannedDate = plannedDate;
    this.status = status;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getScheduleId() {
    return scheduleId;
  }

  public LocalDate getPlannedDate() {
    return plannedDate;
  }

  public PmScheduleDateStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Status transition (F5: executed/missed/rescheduled outcome). */
  public void transitionTo(PmScheduleDateStatus status, Instant updatedAt) {
    this.status = status;
    this.updatedAt = updatedAt;
  }
}
