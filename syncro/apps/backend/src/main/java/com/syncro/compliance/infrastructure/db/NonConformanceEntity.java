package com.syncro.compliance.infrastructure.db;

import com.syncro.compliance.domain.NcStatus;
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
 * Persisted {@code non_conformances} row (blueprint H1, story 15-2). An IATF
 * non-conformance optionally tied to a project, workorder (VARCHAR(50) reference),
 * or machine; {@code nc_number} is the unique business identifier.
 */
@Entity
@Table(name = "non_conformances")
public class NonConformanceEntity {

  @Id
  private UUID id;

  @Column(name = "project_id", length = 64)
  private String projectId;

  @Column(name = "work_order_id", length = 50)
  private String workOrderId;

  @Column(name = "machine_id")
  private UUID machineId;

  @Column(name = "nc_number", nullable = false, length = 50)
  private String ncNumber;

  @Column(nullable = false, columnDefinition = "text")
  private String description;

  @Column(name = "root_cause", columnDefinition = "text")
  private String rootCause;

  @Column(name = "corrective_action", columnDefinition = "text")
  private String correctiveAction;

  @Column(name = "responsible_id")
  private UUID responsibleId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private NcStatus status;

  @Column(name = "target_close_date")
  private LocalDate targetCloseDate;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected NonConformanceEntity() {
  }

  public NonConformanceEntity(UUID id, String projectId, String workOrderId, UUID machineId,
      String ncNumber, String description, String rootCause, String correctiveAction,
      UUID responsibleId, NcStatus status, LocalDate targetCloseDate, Instant closedAt,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.projectId = projectId;
    this.workOrderId = workOrderId;
    this.machineId = machineId;
    this.ncNumber = ncNumber;
    this.description = description;
    this.rootCause = rootCause;
    this.correctiveAction = correctiveAction;
    this.responsibleId = responsibleId;
    this.status = status;
    this.targetCloseDate = targetCloseDate;
    this.closedAt = closedAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getProjectId() {
    return projectId;
  }

  public String getWorkOrderId() {
    return workOrderId;
  }

  public UUID getMachineId() {
    return machineId;
  }

  public String getNcNumber() {
    return ncNumber;
  }

  public String getDescription() {
    return description;
  }

  public String getRootCause() {
    return rootCause;
  }

  public String getCorrectiveAction() {
    return correctiveAction;
  }

  public UUID getResponsibleId() {
    return responsibleId;
  }

  public NcStatus getStatus() {
    return status;
  }

  public LocalDate getTargetCloseDate() {
    return targetCloseDate;
  }

  public Instant getClosedAt() {
    return closedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Closure transition (H1): stamps the close time with the final analysis. */
  public void close(String rootCause, String correctiveAction, Instant closedAt,
      Instant updatedAt) {
    this.rootCause = rootCause;
    this.correctiveAction = correctiveAction;
    this.closedAt = closedAt;
    this.updatedAt = updatedAt;
  }
}
