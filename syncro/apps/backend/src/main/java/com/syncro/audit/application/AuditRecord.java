package com.syncro.audit.application;

import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import java.util.Map;
import java.util.UUID;

public record AuditRecord(
    AuditAction action,
    AuditEntityType entityType,
    UUID entityId,
    String entityLabel,
    UUID plantId,
    Map<String, Object> previousValue,
    Map<String, Object> newValue) {
}
