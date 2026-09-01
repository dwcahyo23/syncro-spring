package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.domain.workorder.WorkLogStoppedReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code work_logs} row (blueprint B4, AD-18, story 15-2). One execution
 * session: start/stop timestamps with a nullable stop reason, mandatory activity
 * note. {@code work_order_id} is the VARCHAR(50) work_orders PK; the workassignment
 * link is a plain UUID column (AD-3/AD-4).
 */
@Entity
@Table(name = "work_logs")
public class WorkLogEntity {

  @Id
  private UUID id;

  @Column(name = "work_assignment_id")
  private UUID workAssignmentId;

  @Column(name = "work_order_id", nullable = false, length = 50)
  private String workOrderId;

  @Column(name = "technician_id", nullable = false)
  private UUID technicianId;

  @Column(name = "start_time", nullable = false)
  private Instant startTime;

  @Column(name = "end_time")
  private Instant endTime;

  @Enumerated(EnumType.STRING)
  @Column(name = "stopped_reason", length = 24)
  private WorkLogStoppedReason stoppedReason;

  @Column(name = "activity_note", nullable = false, length = 2000)
  private String activityNote;

  @Column(name = "completion_note", columnDefinition = "text")
  private String completionNote;

  @Column(columnDefinition = "text")
  private String notes;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WorkLogEntity() {
  }

  public WorkLogEntity(UUID id, UUID workAssignmentId, String workOrderId, UUID technicianId,
      Instant startTime, Instant endTime, WorkLogStoppedReason stoppedReason, String activityNote,
      String completionNote, String notes, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.workAssignmentId = workAssignmentId;
    this.workOrderId = workOrderId;
    this.technicianId = technicianId;
    this.startTime = startTime;
    this.endTime = endTime;
    this.stoppedReason = stoppedReason;
    this.activityNote = activityNote;
    this.completionNote = completionNote;
    this.notes = notes;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkAssignmentId() {
    return workAssignmentId;
  }

  public String getWorkOrderId() {
    return workOrderId;
  }

  public UUID getTechnicianId() {
    return technicianId;
  }

  public Instant getStartTime() {
    return startTime;
  }

  public Instant getEndTime() {
    return endTime;
  }

  public WorkLogStoppedReason getStoppedReason() {
    return stoppedReason;
  }

  public String getActivityNote() {
    return activityNote;
  }

  public String getCompletionNote() {
    return completionNote;
  }

  public String getNotes() {
    return notes;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Stops the session: end timestamp plus the reason it stopped. */
  public void stop(Instant endTime, WorkLogStoppedReason stoppedReason, Instant updatedAt) {
    this.endTime = endTime;
    this.stoppedReason = stoppedReason;
    this.updatedAt = updatedAt;
  }
}
