package com.syncro.maintenance.preventive.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code preventive_schedule_attachments} row (FR-132, story 11-2, AD-10).
 * Mirrors {@code workorder_attachments}: object-key-only — file bytes live in Garage;
 * {@code updated_at} stays null until a replace (PUT) writes a new file onto the row.
 */
@Entity
@Table(name = "preventive_schedule_attachments")
public class PreventiveScheduleAttachmentEntity {

  @Id
  private UUID id;

  @Column(name = "schedule_id", nullable = false)
  private UUID scheduleId;

  @Column(nullable = false, length = 255)
  private String filename;

  @Column(name = "content_type", nullable = false, length = 100)
  private String contentType;

  @Column(name = "object_key", nullable = false, length = 512)
  private String objectKey;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "uploaded_by", nullable = false)
  private UUID uploadedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  protected PreventiveScheduleAttachmentEntity() {
  }

  public PreventiveScheduleAttachmentEntity(UUID id, UUID scheduleId, String filename, String contentType,
      String objectKey, long sizeBytes, UUID uploadedBy, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.scheduleId = scheduleId;
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

  public UUID getId() { return id; }
  public UUID getScheduleId() { return scheduleId; }
  public String getFilename() { return filename; }
  public String getContentType() { return contentType; }
  public String getObjectKey() { return objectKey; }
  public long getSizeBytes() { return sizeBytes; }
  public UUID getUploadedBy() { return uploadedBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
