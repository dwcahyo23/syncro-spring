package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code repair_sessions} row (story 10-4). Non-overlap per workorder is
 * DB-enforced by the {@code excl_repair_sessions_no_overlap} gist EXCLUDE constraint;
 * the service pre-checks for friendly 409s and the constraint is the authoritative
 * backstop for concurrent inserts.
 */
@Entity
@Table(name = "repair_sessions")
public class RepairSessionEntity {

  @Id
  private UUID id;

  @Column(name = "work_order_id", nullable = false, length = 50)
  private String workOrderId;

  @Column(name = "technician_id", nullable = false)
  private UUID technicianId;

  @Column(length = 2000)
  private String description;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  @Column(name = "duration_minutes")
  private Long durationMinutes;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected RepairSessionEntity() {
  }

  public RepairSessionEntity(UUID id, String workOrderId, UUID technicianId, String description,
      Instant startedAt, Instant endedAt, Long durationMinutes, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.workOrderId = workOrderId;
    this.technicianId = technicianId;
    this.description = description;
    this.startedAt = startedAt;
    this.endedAt = endedAt;
    this.durationMinutes = durationMinutes;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getWorkOrderId() {
    return workOrderId;
  }

  public UUID getTechnicianId() {
    return technicianId;
  }

  public String getDescription() {
    return description;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getEndedAt() {
    return endedAt;
  }

  public Long getDurationMinutes() {
    return durationMinutes;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Closes the interval: server clock only, {@code durationMinutes} truncated to whole minutes. */
  public void close(Instant endedAt, Long durationMinutes, Instant updatedAt) {
    this.endedAt = endedAt;
    this.durationMinutes = durationMinutes;
    this.updatedAt = updatedAt;
  }
}
