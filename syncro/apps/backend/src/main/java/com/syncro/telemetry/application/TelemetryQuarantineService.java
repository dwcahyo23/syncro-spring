package com.syncro.telemetry.application;

import com.syncro.telemetry.infrastructure.TelemetryQuarantineEntity;
import com.syncro.telemetry.infrastructure.TelemetryQuarantineRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TelemetryQuarantineService {

  private static final Logger log = LoggerFactory.getLogger(TelemetryQuarantineService.class);
  // Cap payload before persistence to protect memory/logging. PostgreSQL TEXT is unbounded
  // but we guard against extreme-length messages from misbehaving devices.
  // Sentinel is appended AFTER the cut so the total stored length is MAX_PAYLOAD_LENGTH.
  private static final String TRUNCATED_SENTINEL = "[TRUNCATED]";
  private static final int MAX_PAYLOAD_LENGTH = 65535 - TRUNCATED_SENTINEL.length();

  private final TelemetryQuarantineRepository repository;

  public TelemetryQuarantineService(TelemetryQuarantineRepository repository) {
    this.repository = repository;
  }

  private static final int MAX_REASON_LENGTH = 100;

  public void quarantine(TelemetryEnvelope envelope, TelemetryValidationService.Result.Rejected rejected) {
    try {
      var entity = new TelemetryQuarantineEntity();
      entity.setId(UUID.randomUUID());
      entity.setTraceId(envelope.traceId());
      entity.setTopic(envelope.topic());
      entity.setRawPayload(truncate(envelope.payload()));
      entity.setRejectionReason(truncateField(rejected.reason(), MAX_REASON_LENGTH));
      entity.setRejectionField(truncateField(rejected.field(), MAX_REASON_LENGTH));
      entity.setReceivedAt(envelope.receivedAt());
      entity.setCreatedAt(Instant.now());
      repository.save(entity);
    } catch (Exception e) {
      log.warn("mqtt_telemetry_quarantine_failed traceId={} reason={}", envelope.traceId(), rejected.reason(), e);
    }
  }

  static String truncate(String payload) {
    if (payload == null) {
      return "";
    }
    if (payload.length() <= MAX_PAYLOAD_LENGTH) {
      return payload;
    }
    // Use codePointCount-safe boundary to avoid splitting a surrogate pair
    int safeCut = payload.offsetByCodePoints(0,
        payload.codePointCount(0, MAX_PAYLOAD_LENGTH));
    return payload.substring(0, safeCut) + TRUNCATED_SENTINEL;
  }

  static String truncateField(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }
}
