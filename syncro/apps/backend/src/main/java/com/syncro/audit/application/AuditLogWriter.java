package com.syncro.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.audit.infrastructure.AuditLogEntity;
import com.syncro.audit.infrastructure.AuditLogRepository;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.authz.application.DecisionContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogWriter {
  private static final Logger log = LoggerFactory.getLogger(AuditLogWriter.class);
  private final AuditLogRepository auditLogs;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock clock;

  public AuditLogWriter(AuditLogRepository auditLogs, Clock clock) {
    this.auditLogs = auditLogs;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRED)
  public void recordSystem(AuditRecord record) {
    auditLogs.save(new AuditLogEntity(
        UUID.randomUUID(),
        new UUID(0L, 0L),
        "SYSTEM",
        record.action(),
        record.entityType(),
        record.entityId(),
        record.entityLabel(),
        record.plantId(),
        writeJson(record.previousValue()),
        writeJson(record.newValue()),
        Instant.now(clock),
        resolveDecisionId(record)));
  }

  @Transactional(propagation = Propagation.REQUIRED)
  public void record(AuthenticatedUser actor, AuditRecord record) {
    auditLogs.save(new AuditLogEntity(
        UUID.randomUUID(),
        UUID.fromString(actor.id()),
        actor.loginIdentifier(),
        record.action(),
        record.entityType(),
        record.entityId(),
        record.entityLabel(),
        record.plantId(),
        writeJson(record.previousValue()),
        writeJson(record.newValue()),
        Instant.now(clock),
        resolveDecisionId(record)));
  }

  /** Explicit value wins; otherwise correlate from the request's stashed OPA decision id. */
  private UUID resolveDecisionId(AuditRecord record) {
    if (record.decisionId() != null) {
      return record.decisionId();
    }
    var stashed = DecisionContext.currentDecisionId();
    if (stashed == null) {
      return null;
    }
    try {
      return UUID.fromString(stashed);
    } catch (IllegalArgumentException exception) {
      // FR-164 correlation silently lost would hide a producer bug — surface it.
      log.warn("[AUDIT] Non-UUID stashed decision_id dropped value={}", stashed);
      return null;
    }
  }

  private String writeJson(Map<String, Object> value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize audit values", exception);
    }
  }
}
