package com.syncro.audit.application;

import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import java.util.Map;
import java.util.UUID;

/**
 * Audit entry to persist. {@code decisionId} is the OPA decision id for correlation
 * (FR-164); when null, {@code AuditLogWriter} autofills it from the current request's
 * stashed decision via {@code DecisionContext}.
 */
public record AuditRecord(
    AuditAction action,
    AuditEntityType entityType,
    UUID entityId,
    String entityLabel,
    UUID plantId,
    Map<String, Object> previousValue,
    Map<String, Object> newValue,
    UUID decisionId) {
}
