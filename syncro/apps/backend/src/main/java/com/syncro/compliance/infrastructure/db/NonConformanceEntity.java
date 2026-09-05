package com.syncro.compliance.infrastructure.db;

import com.syncro.compliance.domain.NcSeverity;
import com.syncro.compliance.domain.NcStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

  @Enumerated(EnumType.STRING)
  @Column(nullable = true, length = 16)
  private NcSeverity severity;

  @Column(name = "target_close_date")
  private LocalDate targetCloseDate;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Optimistic lock (review 21-1 P6): concurrent PATCH/verify → 409 VERSION_CONFLICT. */
  @Version
  @Column(nullable = false)
  private long version;

  protected NonConformanceEntity() {
  }

  /** Story 15-2 shape (no severity) — delegates with a null severity. */
  public NonConformanceEntity(UUID id, String projectId, String workOrderId, UUID machineId,
      String ncNumber, String description, String rootCause, String correctiveAction,
      UUID responsibleId, NcStatus status, LocalDate targetCloseDate, Instant closedAt,
      Instant createdAt, Instant updatedAt) {
    this(id, projectId, workOrderId, machineId, ncNumber, description, rootCause, correctiveAction,
        responsibleId, status, null, targetCloseDate, closedAt, createdAt, updatedAt);
  }

  public NonConformanceEntity(UUID id, String projectId, String workOrderId, UUID machineId,
      String ncNumber, String description, String rootCause, String correctiveAction,
      UUID responsibleId, NcStatus status, NcSeverity severity, LocalDate targetCloseDate,
      Instant closedAt, Instant createdAt, Instant updatedAt) {
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
    this.severity = severity;
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

  public NcSeverity getSeverity() {
    return severity;
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

  public long getVersion() {
    return version;
  }

  /** Closure transition (H1): stamps the close time with the final analysis. */
  public void close(String rootCause, String correctiveAction, Instant closedAt,
      Instant updatedAt) {
    this.rootCause = rootCause;
    this.correctiveAction = correctiveAction;
    this.closedAt = closedAt;
    this.updatedAt = updatedAt;
  }

  /** Status transition (story 21-1): moves the lifecycle and stamps updatedAt. */
  public void transitionTo(NcStatus status, Instant updatedAt) {
    this.status = status;
    this.updatedAt = updatedAt;
  }

  /**
   * Partial update (story 21-1 PATCH, KPI-target precedent): a null argument keeps
   * the stored value. {@code ncNumber} is immutable after creation.
   */
  public void updateContent(String description, NcSeverity severity, String rootCause,
      String correctiveAction, UUID responsibleId, LocalDate targetCloseDate, Instant updatedAt) {
    if (description != null) {
      this.description = description;
    }
    if (severity != null) {
      this.severity = severity;
    }
    if (rootCause != null) {
      this.rootCause = rootCause;
    }
    if (correctiveAction != null) {
      this.correctiveAction = correctiveAction;
    }
    if (responsibleId != null) {
      this.responsibleId = responsibleId;
    }
    if (targetCloseDate != null) {
      this.targetCloseDate = targetCloseDate;
    }
    this.updatedAt = updatedAt;
  }
}
