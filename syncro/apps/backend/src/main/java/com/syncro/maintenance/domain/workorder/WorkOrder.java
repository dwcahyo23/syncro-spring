package com.syncro.maintenance.domain.workorder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Application-level workorder value (AD-3/AD-4). The id is dual-source: an external
 * {@code sheet_no} (source SYNCED) or {@code WO-YYMM-XXXXX} (source INTERNAL).
 * Cross-aggregate references stay as plain ids — this record carries no JPA/Spring
 * state and is mapped to/from the persistence entity by {@code WorkOrderMapper}.
 *
 * <p>The report fields (10-6, FR-117/FR-118/FR-122) are a single aggregate written
 * atomically with the workorder row: a four-section narrative, optional CP/CPK
 * capability values + a CP/CPK PDF object key (AD-10 — only the key is stored, never
 * bytes or presigned URLs), an optional FMEA failure-type tag (v1 tags only), and an
 * optional stop-time reason + detail. CP/CPK is never mandatory on any category; the
 * stop-time reason is required only when completing a Breakdown (01) workorder.
 */
public record WorkOrder(
    String id,
    String source,
    WorkOrderStatus status,
    UUID categoryId,
    UUID machineId,
    String description,
    String parentId,
    UUID assignedTechnicianId,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt,
    Long mttrMinutes,
    Long responseTimeMinutes,
    String doneReason,
    String reportChronological,
    String reportAnalyze,
    String reportCorrective,
    String reportPreventive,
    BigDecimal cpCkLower,
    BigDecimal cpCkUpper,
    BigDecimal cpk,
    String cpkPdfObjectKey,
    String fmeaFailureType,
    String stopTimeReason,
    String stopTimeDetail) {
}
