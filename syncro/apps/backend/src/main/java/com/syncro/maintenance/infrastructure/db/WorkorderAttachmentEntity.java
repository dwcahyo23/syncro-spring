package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code workorder_attachments} row (story 10-5, AD-10). Object-key-only:
 * file bytes live in Garage; {@code updated_at} is null until a replace (PUT) writes
 * a new file onto the same row.
 */
@Entity
@Table(name = "workorder_attachments")
public class WorkorderAttachmentEntity {

  @Id
  private UUID id;

  @Column(name = "work_order_id", nullable = false, length = 50)
  private String workOrderId;

  @Column(nullable = false, length = 255)
  private String filename;

  @Column(name = "content_type", nullable = false, length = 100)
  private String contentType;

  @Column(name = "object_key", nullable = false, length = 255)
  private String objectKey;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "uploaded_by", nullable = false)
  private UUID uploadedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  protected WorkorderAttachmentEntity() {
  }

  public WorkorderAttachmentEntity(UUID id, String workOrderId, String filename, String contentType,
      String objectKey, long sizeBytes, UUID uploadedBy, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.workOrderId = workOrderId;
    this.filename = filename;
    this.contentType = contentType;
    this.objectKey = objectKey;
    this.sizeBytes = sizeBytes;
    this.uploadedBy = uploadedBy;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  /** Replaces the stored file (PUT): new object key + metadata, server clock only. */
  public void replace(String filename, String contentType, String objectKey, long sizeBytes, Instant updatedAt) {
    this.filename = filename;
    this.contentType = contentType;
    this.objectKey = objectKey;
    this.sizeBytes = sizeBytes;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getWorkOrderId() {
    return workOrderId;
  }

  public String getFilename() {
    return filename;
  }

  public String getContentType() {
    return contentType;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public UUID getUploadedBy() {
    return uploadedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
