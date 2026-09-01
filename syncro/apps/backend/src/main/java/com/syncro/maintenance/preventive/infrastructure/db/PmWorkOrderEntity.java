package com.syncro.maintenance.preventive.infrastructure.db;

import com.syncro.maintenance.preventive.domain.PmWorkOrderStatus;
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
 * Persisted {@code pm_work_orders} row (blueprint F6, story 15-2). A generated
 * preventive workorder — NOT a {@code work_orders} row; it has its own status
 * lifecycle and UUID PK. Template/frequency fields are denormalized copies of the
 * generating checksheet configuration.
 */
@Entity
@Table(name = "pm_work_orders")
public class PmWorkOrderEntity {

  @Id
  private UUID id;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Column(name = "template_id")
  private UUID templateId;

  @Column(name = "frequency_id")
  private UUID frequencyId;

  @Column(name = "frequency_code", length = 50)
  private String frequencyCode;

  @Column(name = "frequency_name", length = 200)
  private String frequencyName;

  @Column(name = "template_revision")
  private Integer templateRevision;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private PmWorkOrderStatus status;

  @Column(name = "assigned_technician_id")
  private UUID assignedTechnicianId;

  @Column(name = "scheduled_date")
  private LocalDate scheduledDate;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "certificate_url", columnDefinition = "text")
  private String certificateUrl;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PmWorkOrderEntity() {
  }

  public PmWorkOrderEntity(UUID id, UUID machineId, UUID templateId, UUID frequencyId,
      String frequencyCode, String frequencyName, Integer templateRevision,
      PmWorkOrderStatus status, UUID assignedTechnicianId, LocalDate scheduledDate,
      Instant startedAt, Instant completedAt, String certificateUrl, Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.machineId = machineId;
    this.templateId = templateId;
    this.frequencyId = frequencyId;
    this.frequencyCode = frequencyCode;
    this.frequencyName = frequencyName;
    this.templateRevision = templateRevision;
    this.status = status;
    this.assignedTechnicianId = assignedTechnicianId;
    this.scheduledDate = scheduledDate;
    this.startedAt = startedAt;
    this.completedAt = completedAt;
    this.certificateUrl = certificateUrl;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getMachineId() {
    return machineId;
  }

  public UUID getTemplateId() {
    return templateId;
  }

  public UUID getFrequencyId() {
    return frequencyId;
  }

  public String getFrequencyCode() {
    return frequencyCode;
  }

  public String getFrequencyName() {
    return frequencyName;
  }

  public Integer getTemplateRevision() {
    return templateRevision;
  }

  public PmWorkOrderStatus getStatus() {
    return status;
  }

  public UUID getAssignedTechnicianId() {
    return assignedTechnicianId;
  }

  public LocalDate getScheduledDate() {
    return scheduledDate;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public String getCertificateUrl() {
    return certificateUrl;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Assignment transition (F6): records the technician and the status move. */
  public void assign(UUID assignedTechnicianId, Instant updatedAt) {
    this.assignedTechnicianId = assignedTechnicianId;
    this.status = PmWorkOrderStatus.ASSIGNED;
    this.updatedAt = updatedAt;
  }

  /** Completion transition (F6): stamps the completion time and certificate. */
  public void complete(Instant completedAt, String certificateUrl, Instant updatedAt) {
    this.completedAt = completedAt;
    this.certificateUrl = certificateUrl;
    this.status = PmWorkOrderStatus.COMPLETED;
    this.updatedAt = updatedAt;
  }
}
