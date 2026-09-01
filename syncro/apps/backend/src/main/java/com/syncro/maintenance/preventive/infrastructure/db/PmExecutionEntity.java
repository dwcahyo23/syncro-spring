package com.syncro.maintenance.preventive.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code pm_executions} row (blueprint F7, story 15-2). One execution of a
 * PM workorder: technician + SPV signature bookends (signature ids reference
 * {@code user_signatures} as plain UUID columns, DP4), NG summary counters, and the
 * optional corrective {@code finding_wo_id} VARCHAR(50) reference to work_orders.
 */
@Entity
@Table(name = "pm_executions")
public class PmExecutionEntity {

  @Id
  private UUID id;

  @Column(name = "pm_wo_id", nullable = false)
  private UUID pmWoId;

  @Column(name = "schedule_date_id")
  private UUID scheduleDateId;

  @Column(name = "technician_id", nullable = false)
  private UUID technicianId;

  @Column(name = "spv_verifier_id")
  private UUID spvVerifierId;

  @Column(name = "technician_signature_id")
  private UUID technicianSignatureId;

  @Column(name = "technician_signed_at")
  private Instant technicianSignedAt;

  @Column(name = "spv_signature_id")
  private UUID spvSignatureId;

  @Column(name = "spv_signed_at")
  private Instant spvSignedAt;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "has_ng_items", nullable = false)
  private boolean hasNgItems;

  @Column(name = "ng_count", nullable = false)
  private int ngCount;

  @Column(name = "finding_wo_id", length = 50)
  private String findingWoId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PmExecutionEntity() {
  }

  public PmExecutionEntity(UUID id, UUID pmWoId, UUID scheduleDateId, UUID technicianId,
      UUID spvVerifierId, UUID technicianSignatureId, Instant technicianSignedAt,
      UUID spvSignatureId, Instant spvSignedAt, Instant startedAt, Instant completedAt,
      boolean hasNgItems, int ngCount, String findingWoId, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.pmWoId = pmWoId;
    this.scheduleDateId = scheduleDateId;
    this.technicianId = technicianId;
    this.spvVerifierId = spvVerifierId;
    this.technicianSignatureId = technicianSignatureId;
    this.technicianSignedAt = technicianSignedAt;
    this.spvSignatureId = spvSignatureId;
    this.spvSignedAt = spvSignedAt;
    this.startedAt = startedAt;
    this.completedAt = completedAt;
    this.hasNgItems = hasNgItems;
    this.ngCount = ngCount;
    this.findingWoId = findingWoId;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPmWoId() {
    return pmWoId;
  }

  public UUID getScheduleDateId() {
    return scheduleDateId;
  }

  public UUID getTechnicianId() {
    return technicianId;
  }

  public UUID getSpvVerifierId() {
    return spvVerifierId;
  }

  public UUID getTechnicianSignatureId() {
    return technicianSignatureId;
  }

  public Instant getTechnicianSignedAt() {
    return technicianSignedAt;
  }

  public UUID getSpvSignatureId() {
    return spvSignatureId;
  }

  public Instant getSpvSignedAt() {
    return spvSignedAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public boolean hasNgItems() {
    return hasNgItems;
  }

  public int getNgCount() {
    return ngCount;
  }

  public String getFindingWoId() {
    return findingWoId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** SPV verification transition (F7): stamps verifier, signature, and time. */
  public void verifyBySpv(UUID spvVerifierId, UUID spvSignatureId, Instant spvSignedAt,
      Instant updatedAt) {
    this.spvVerifierId = spvVerifierId;
    this.spvSignatureId = spvSignatureId;
    this.spvSignedAt = spvSignedAt;
    this.updatedAt = updatedAt;
  }

  /** Completion transition (F7): stamps the completion time and NG rollup. */
  public void complete(Instant completedAt, boolean hasNgItems, int ngCount, Instant updatedAt) {
    this.completedAt = completedAt;
    this.hasNgItems = hasNgItems;
    this.ngCount = ngCount;
    this.updatedAt = updatedAt;
  }
}
