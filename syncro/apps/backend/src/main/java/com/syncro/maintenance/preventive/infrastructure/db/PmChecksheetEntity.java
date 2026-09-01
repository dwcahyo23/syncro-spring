package com.syncro.maintenance.preventive.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted {@code pm_checksheets} row (blueprint F2, story 15-2). A revision of a
 * machine+frequency checksheet; {@code supersedes} self-references the previous
 * revision (plain UUID column, AD-3). Unique {@code (machine_id, frequency_id,
 * revision_no)} via V1 constraint.
 */
@Entity
@Table(name = "pm_checksheets")
public class PmChecksheetEntity {

  @Id
  private UUID id;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Column(name = "frequency_id", nullable = false)
  private UUID frequencyId;

  @Column(name = "revision_no", nullable = false)
  private int revisionNo;

  @Column(name = "revision_reason", columnDefinition = "text")
  private String revisionReason;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(name = "supersedes")
  private UUID supersedes;

  @Column(name = "approved_by")
  private UUID approvedBy;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "effective_date")
  private LocalDate effectiveDate;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PmChecksheetEntity() {
  }

  public PmChecksheetEntity(UUID id, UUID machineId, UUID frequencyId, int revisionNo,
      String revisionReason, boolean active, UUID supersedes, UUID approvedBy, Instant approvedAt,
      LocalDate effectiveDate, UUID createdBy, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.machineId = machineId;
    this.frequencyId = frequencyId;
    this.revisionNo = revisionNo;
    this.revisionReason = revisionReason;
    this.active = active;
    this.supersedes = supersedes;
    this.approvedBy = approvedBy;
    this.approvedAt = approvedAt;
    this.effectiveDate = effectiveDate;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getMachineId() {
    return machineId;
  }

  public UUID getFrequencyId() {
    return frequencyId;
  }

  public int getRevisionNo() {
    return revisionNo;
  }

  public String getRevisionReason() {
    return revisionReason;
  }

  public boolean isActive() {
    return active;
  }

  public UUID getSupersedes() {
    return supersedes;
  }

  public UUID getApprovedBy() {
    return approvedBy;
  }

  public Instant getApprovedAt() {
    return approvedAt;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Approval transition (F2): stamps approver, verdict time, and effective date. */
  public void approve(UUID approvedBy, Instant approvedAt, LocalDate effectiveDate,
      Instant updatedAt) {
    this.approvedBy = approvedBy;
    this.approvedAt = approvedAt;
    this.effectiveDate = effectiveDate;
    this.updatedAt = updatedAt;
  }
}
