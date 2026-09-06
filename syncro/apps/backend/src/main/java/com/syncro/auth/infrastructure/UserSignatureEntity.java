package com.syncro.auth.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code user_signatures} row (blueprint I1, DP4, story 15-2). One stored
 * signature image per user (Garage object reference); signature usage events point
 * back here through {@link SignatureUseEntity#signatureId}. Uniqueness per user via
 * V1 constraint; blocked-until backs the failed-attempt lockout. Story 22-3: the
 * {@code sha256} column (V16) carries the image-bytes hash computed at upload — the
 * enriched signature-application paths (WO approve with a signatureId, PM execution
 * verify) copy it onto the {@code signature_uses} row as the signature reference at
 * signing time; reference-less rows (legacy WO approve, PM checklist approve)
 * deliberately store null. Upload upserts replace the object reference in place via
 * {@link #replaceContent} (the single-row-per-user invariant keeps the id stable).
 */
@Entity
@Table(name = "user_signatures")
public class UserSignatureEntity {

  @Id
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(nullable = false, length = 255)
  private String bucket;

  @Column(name = "object_key", nullable = false, length = 512)
  private String objectKey;

  @Column(name = "content_type", length = 100)
  private String contentType;

  @Column(name = "sha256", length = 64)
  private String sha256;

  @Column(name = "signature_failed_attempts", nullable = false)
  private int signatureFailedAttempts;

  @Column(name = "signature_blocked_until")
  private Instant signatureBlockedUntil;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected UserSignatureEntity() {
  }

  public UserSignatureEntity(UUID id, UUID userId, String bucket, String objectKey,
      String contentType, int signatureFailedAttempts, Instant signatureBlockedUntil,
      Instant createdAt, Instant updatedAt) {
    this(id, userId, bucket, objectKey, contentType, null, signatureFailedAttempts,
        signatureBlockedUntil, createdAt, updatedAt);
  }

  public UserSignatureEntity(UUID id, UUID userId, String bucket, String objectKey,
      String contentType, String sha256, int signatureFailedAttempts,
      Instant signatureBlockedUntil, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.userId = userId;
    this.bucket = bucket;
    this.objectKey = objectKey;
    this.contentType = contentType;
    this.sha256 = sha256;
    this.signatureFailedAttempts = signatureFailedAttempts;
    this.signatureBlockedUntil = signatureBlockedUntil;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getBucket() {
    return bucket;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public String getContentType() {
    return contentType;
  }

  public String getSha256() {
    return sha256;
  }

  public int getSignatureFailedAttempts() {
    return signatureFailedAttempts;
  }

  public Instant getSignatureBlockedUntil() {
    return signatureBlockedUntil;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /**
   * Upsert replacement (story 22-3): points the row at the freshly-stored Garage
   * object and its hash. The id stays stable so existing {@code signature_uses}
   * references keep resolving; the superseded object is deleted by the service
   * after commit.
   */
  public void replaceContent(String bucket, String objectKey, String contentType,
      String sha256, Instant updatedAt) {
    this.bucket = bucket;
    this.objectKey = objectKey;
    this.contentType = contentType;
    this.sha256 = sha256;
    this.updatedAt = updatedAt;
  }
}
