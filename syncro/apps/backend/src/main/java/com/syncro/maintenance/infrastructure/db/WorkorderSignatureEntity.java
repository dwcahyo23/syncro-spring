package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code workorder_signatures} row (story 14-3, FR-175). One per workorder
 * (unique {@code work_order_id}), bound at DONE/CLOSED by an in-scope leader/SPV.
 * Signature image bytes live in Garage under
 * {@code workorders/{workOrderId}/signature/{uuid}.{ext}}; PostgreSQL stores only the
 * object key plus signer metadata.
 */
@Entity
@Table(name = "workorder_signatures")
public class WorkorderSignatureEntity {

  @Id
  private UUID id;

  @Column(name = "work_order_id", nullable = false, length = 50)
  private String workOrderId;

  @Column(name = "signature_object_key", nullable = false, length = 512)
  private String signatureObjectKey;

  @Column(name = "signer_identity", nullable = false, length = 200)
  private String signerIdentity;

  @Column(name = "signed_by", nullable = false)
  private UUID signedBy;

  @Column(name = "signed_at", nullable = false)
  private Instant signedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WorkorderSignatureEntity() {
  }

  public WorkorderSignatureEntity(UUID id, String workOrderId, String signatureObjectKey, String signerIdentity,
      UUID signedBy, Instant signedAt, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.workOrderId = workOrderId;
    this.signatureObjectKey = signatureObjectKey;
    this.signerIdentity = signerIdentity;
    this.signedBy = signedBy;
    this.signedAt = signedAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getWorkOrderId() {
    return workOrderId;
  }

  public String getSignatureObjectKey() {
    return signatureObjectKey;
  }

  public String getSignerIdentity() {
    return signerIdentity;
  }

  public UUID getSignedBy() {
    return signedBy;
  }

  public Instant getSignedAt() {
    return signedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
