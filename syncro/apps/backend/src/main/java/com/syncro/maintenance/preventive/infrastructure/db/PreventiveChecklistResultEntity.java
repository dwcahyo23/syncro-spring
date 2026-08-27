package com.syncro.maintenance.preventive.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code preventive_checklist_results} row (FR-132, story 11-2). One per
 * schedule (unique {@code schedule_id}); created by the technician who performs the
 * check. Approval fills {@code leader_id}/{@code assessment}/{@code approved_at}/
 * {@code signature_object_key}/{@code signer_identity} on the same row.
 */
@Entity
@Table(name = "preventive_checklist_results")
public class PreventiveChecklistResultEntity {

  @Id
  private UUID id;

  @Column(name = "schedule_id", nullable = false)
  private UUID scheduleId;

  @Column(name = "performed_by", nullable = false)
  private UUID performedBy;

  @Column(name = "completed_at", nullable = false)
  private Instant completedAt;

  @Column(columnDefinition = "text")
  private String notes;

  @Column(name = "leader_id")
  private UUID leaderId;

  @Column(columnDefinition = "text")
  private String assessment;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "signature_object_key", length = 512)
  private String signatureObjectKey;

  @Column(name = "signer_identity", length = 200)
  private String signerIdentity;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PreventiveChecklistResultEntity() {
  }

  public PreventiveChecklistResultEntity(UUID id, UUID scheduleId, UUID performedBy, Instant completedAt,
      String notes, UUID leaderId, String assessment, Instant approvedAt, String signatureObjectKey,
      String signerIdentity, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.scheduleId = scheduleId;
    this.performedBy = performedBy;
    this.completedAt = completedAt;
    this.notes = notes;
    this.leaderId = leaderId;
    this.assessment = assessment;
    this.approvedAt = approvedAt;
    this.signatureObjectKey = signatureObjectKey;
    this.signerIdentity = signerIdentity;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  /** Leader approval fills the assessment + signature fields (server clock only). */
  public void approve(String assessment, String signatureObjectKey, String signerIdentity, UUID leaderId,
      Instant approvedAt, Instant updatedAt) {
    this.assessment = assessment;
    this.signatureObjectKey = signatureObjectKey;
    this.signerIdentity = signerIdentity;
    this.leaderId = leaderId;
    this.approvedAt = approvedAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() { return id; }
  public UUID getScheduleId() { return scheduleId; }
  public UUID getPerformedBy() { return performedBy; }
  public Instant getCompletedAt() { return completedAt; }
  public String getNotes() { return notes; }
  public UUID getLeaderId() { return leaderId; }
  public String getAssessment() { return assessment; }
  public Instant getApprovedAt() { return approvedAt; }
  public String getSignatureObjectKey() { return signatureObjectKey; }
  public String getSignerIdentity() { return signerIdentity; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
