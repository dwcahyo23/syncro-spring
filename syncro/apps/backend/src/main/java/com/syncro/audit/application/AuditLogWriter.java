package com.syncro.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.audit.infrastructure.AuditLogEntity;
import com.syncro.audit.infrastructure.AuditLogRepository;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogWriter {
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
        Instant.now(clock)));
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
        Instant.now(clock)));
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
