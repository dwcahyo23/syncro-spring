package com.syncro.maintenance.preventive.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One checklist result per preventive schedule (FR-132, story 11-2). Created by the
 * technician/staff who performs the check; approval fills the leader fields and the
 * Garage signature object key. Signature bytes live in Garage — only the key is stored.
 */
public record PreventiveChecklistResult(
    UUID id,
    UUID scheduleId,
    UUID performedBy,
    Instant completedAt,
    String notes,
    UUID leaderId,
    String assessment,
    Instant approvedAt,
    String signatureObjectKey,
    String signerIdentity,
    Instant createdAt,
    Instant updatedAt) {
}
