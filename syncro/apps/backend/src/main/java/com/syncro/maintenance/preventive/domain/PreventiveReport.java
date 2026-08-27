package com.syncro.maintenance.preventive.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Assembled preventive report (FR-133, story 11-3). A data view for browser print:
 * program/machine/schedule context, the checklist result + items, evidence presigned
 * URLs, and the leader signature block. Image bytes never live in this record — only
 * short-TTL presigned URLs derived from Garage object keys.
 */
public record PreventiveReport(
    UUID scheduleId,
    String programTitle,
    PreventiveCategory category,
    ScheduleType scheduleType,
    boolean autoWorkorder,
    UUID machineId,
    LocalDate dueDate,
    ScheduleStatus scheduleStatus,
    Instant completedAt,
    UUID performedBy,
    PreventiveChecklistResult checklist,
    List<PreventiveChecklistItem> items,
    List<EvidenceRef> evidence,
    String signaturePresignedUrl,
    String signerIdentity,
    Instant approvedAt,
    String workOrderId) {

  /** Evidence reference for the report: metadata + fresh presigned URL (never persisted). */
  public record EvidenceRef(UUID id, String filename, String contentType, String presignedUrl) {
  }
}
