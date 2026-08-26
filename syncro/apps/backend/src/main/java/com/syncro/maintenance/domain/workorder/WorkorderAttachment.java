package com.syncro.maintenance.domain.workorder;

import java.time.Instant;
import java.util.UUID;

/**
 * Workorder evidence/technical-drawing value (story 10-5, AD-10). Only the
 * bucket-relative object key is stored in PostgreSQL; file bytes live in Garage and
 * presigned URLs are short-TTL and never persisted. {@code updatedAt} is null until
 * a PUT replaces the file. Attachments are local operational fields (AD-3
 * "preserved") — they never touch the workorder status or sync_version.
 */
public record WorkorderAttachment(
    UUID id,
    String workOrderId,
    String filename,
    String contentType,
    String objectKey,
    long sizeBytes,
    UUID uploadedBy,
    Instant createdAt,
    Instant updatedAt) {
}
