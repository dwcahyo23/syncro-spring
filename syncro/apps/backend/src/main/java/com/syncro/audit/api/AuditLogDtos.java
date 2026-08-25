package com.syncro.audit.api;

import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuditLogDtos {
  private AuditLogDtos() {
  }

  public record AuditLogEntryView(
      UUID id,
      UUID actorId,
      String actorName,
      AuditAction action,
      AuditEntityType entityType,
      UUID entityId,
      String entityLabel,
      UUID plantId,
      Map<String, Object> previousValue,
      Map<String, Object> newValue,
      Instant createdAt,
      UUID decisionId) {
  }

  public record AuditLogListResponse(List<AuditLogEntryView> items, long totalElements, int page, int size, String sort) {
  }
}
