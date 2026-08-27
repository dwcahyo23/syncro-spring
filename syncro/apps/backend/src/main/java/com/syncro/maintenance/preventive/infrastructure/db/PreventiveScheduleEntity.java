package com.syncro.maintenance.preventive.infrastructure.db;

import com.syncro.maintenance.preventive.domain.ScheduleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "preventive_schedules")
public class PreventiveScheduleEntity {

  @Id
  private UUID id;

  @Column(name = "program_id", nullable = false)
  private UUID programId;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Column(name = "due_date", nullable = false)
  private LocalDate dueDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ScheduleStatus status;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "performed_by")
  private UUID performedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PreventiveScheduleEntity() {
  }

  public PreventiveScheduleEntity(UUID id, UUID programId, UUID machineId, LocalDate dueDate, ScheduleStatus status,
      Instant completedAt, UUID performedBy, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.programId = programId;
    this.machineId = machineId;
    this.dueDate = dueDate;
    this.status = status;
    this.completedAt = completedAt;
    this.performedBy = performedBy;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  /**
   * Story 11-2 status transition (SCHEDULED → IN_PROGRESS → PERFORMED, or SKIPPED from
   * SCHEDULED/IN_PROGRESS). Marks completion on PERFORMED only. Server clock values.
   */
  public void transition(ScheduleStatus newStatus, Instant now, UUID actor) {
    this.status = newStatus;
    this.updatedAt = now;
    if (newStatus == ScheduleStatus.PERFORMED) {
      this.completedAt = now;
      this.performedBy = actor;
    }
  }

  public UUID getId() { return id; }
  public UUID getProgramId() { return programId; }
  public UUID getMachineId() { return machineId; }
  public LocalDate getDueDate() { return dueDate; }
  public ScheduleStatus getStatus() { return status; }
  public Instant getCompletedAt() { return completedAt; }
  public UUID getPerformedBy() { return performedBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}