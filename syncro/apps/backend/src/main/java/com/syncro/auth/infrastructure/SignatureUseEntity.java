package com.syncro.auth.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code signature_uses} row (blueprint I1, story 15-1). One row per signed
 * subject; a workorder approval is {@code (module='maintenance', subject_type='WORK_ORDER',
 * subject_id=<wo id>)} — replacing the legacy {@code workorder_signatures} table (DP4).
 * Signature image bytes live in Garage; PostgreSQL stores only object keys plus signer
 * metadata. Uniqueness per subject is enforced for the workorder subject type via a
 * partial unique index (uq_signature_uses_work_order).
 */
@Entity
@Table(name = "signature_uses")
public class SignatureUseEntity {

  @Id
  private UUID id;

  @Column(name = "signer_id")
  private UUID signerId;

  @Column(name = "signature_id")
  private UUID signatureId;

  @Column(nullable = false, length = 50)
  private String module;

  @Column(name = "subject_type", nullable = false, length = 50)
  private String subjectType;

  @Column(name = "subject_id", nullable = false, length = 50)
  private String subjectId;

  @Column(nullable = false, length = 100)
  private String action;

  @Column(length = 2000)
  private String reason;

  @Column(name = "signature_bucket", length = 255)
  private String signatureBucket;

  @Column(name = "signature_object_key", length = 512)
  private String signatureObjectKey;

  @Column(name = "signature_sha256", length = 64)
  private String signatureSha256;

  @Column(name = "signed_artifact_bucket", length = 255)
  private String signedArtifactBucket;

  @Column(name = "signed_artifact_key", length = 512)
  private String signedArtifactKey;

  @Column(name = "ip_address", length = 64)
  private String ipAddress;

  @Column(name = "user_agent", columnDefinition = "text")
  private String userAgent;

  @Column(name = "signed_at", nullable = false)
  private Instant signedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected SignatureUseEntity() {
  }

  public SignatureUseEntity(UUID id, UUID signerId, UUID signatureId, String module,
      String subjectType, String subjectId, String action, String reason, String signatureBucket,
      String signatureObjectKey, String signatureSha256, String signedArtifactBucket,
      String signedArtifactKey, String ipAddress, String userAgent, Instant signedAt,
      Instant createdAt) {
    this.id = id;
    this.signerId = signerId;
    this.signatureId = signatureId;
    this.module = module;
    this.subjectType = subjectType;
    this.subjectId = subjectId;
    this.action = action;
    this.reason = reason;
    this.signatureBucket = signatureBucket;
    this.signatureObjectKey = signatureObjectKey;
    this.signatureSha256 = signatureSha256;
    this.signedArtifactBucket = signedArtifactBucket;
    this.signedArtifactKey = signedArtifactKey;
    this.ipAddress = ipAddress;
    this.userAgent = userAgent;
    this.signedAt = signedAt;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSignerId() {
    return signerId;
  }

  public UUID getSignatureId() {
    return signatureId;
  }

  public String getModule() {
    return module;
  }

  public String getSubjectType() {
    return subjectType;
  }

  public String getSubjectId() {
    return subjectId;
  }

  public String getAction() {
    return action;
  }

  public String getReason() {
    return reason;
  }

  public String getSignatureBucket() {
    return signatureBucket;
  }

  public String getSignatureObjectKey() {
    return signatureObjectKey;
  }

  public String getSignatureSha256() {
    return signatureSha256;
  }

  public String getSignedArtifactBucket() {
    return signedArtifactBucket;
  }

  public String getSignedArtifactKey() {
    return signedArtifactKey;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public Instant getSignedAt() {
    return signedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
