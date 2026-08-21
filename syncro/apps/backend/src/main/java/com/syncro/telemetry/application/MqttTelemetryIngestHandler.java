package com.syncro.telemetry.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Component;

@Component
public class MqttTelemetryIngestHandler implements MessageHandler {

  private static final Logger log = LoggerFactory.getLogger(MqttTelemetryIngestHandler.class);

  private final Clock clock;
  private final TelemetryValidationService validationService;
  private final TelemetryPersistenceService persistenceService;
  private final TelemetryQuarantineService quarantineService;
  private final TelemetryIngestTracker ingestTracker;
  private final TelemetryDataQualityTracker dataQualityTracker;

  public MqttTelemetryIngestHandler(Clock clock, TelemetryValidationService validationService,
      TelemetryPersistenceService persistenceService, TelemetryQuarantineService quarantineService,
      TelemetryIngestTracker ingestTracker, TelemetryDataQualityTracker dataQualityTracker) {
    this.clock = clock;
    this.validationService = validationService;
    this.persistenceService = persistenceService;
    this.quarantineService = quarantineService;
    this.ingestTracker = ingestTracker;
    this.dataQualityTracker = dataQualityTracker;
  }

  public TelemetryEnvelope enrich(Message<?> message) {
    Object topicHeader = message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
    String topic = topicHeader == null ? "" : topicHeader.toString();
    Object payload = message.getPayload();
    String payloadText = payload instanceof byte[] bytes
        ? new String(bytes, StandardCharsets.UTF_8)
        : payload == null ? "" : payload.toString();
    return new TelemetryEnvelope(UUID.randomUUID().toString(), topic, payloadText, Instant.now(clock));
  }

  @Override
  public void handleMessage(Message<?> message) throws MessagingException {
    String traceId = null;
    // Dead-letter is defined as "passed validation but processing threw"; only the Accepted
    // branch sets this, so a quarantine-store or validation failure never counts as one.
    boolean validationPassed = false;
    try {
      TelemetryEnvelope envelope = enrich(message);
      traceId = envelope.traceId();
      switch (validationService.validate(envelope.topic(), envelope.payload())) {
        case TelemetryValidationService.Result.Accepted accepted -> {
          validationPassed = true;
          ingestTracker.recordAccepted();
          dataQualityTracker.recordAccepted();
          log.info("mqtt_telemetry_accepted traceId={} topic={}",
              envelope.traceId(), envelope.topic());
          log.debug(
              "mqtt_telemetry_received traceId={} topic={} payload={}",
              envelope.traceId(), envelope.topic(), envelope.payload());
          persistenceService.persist(accepted, envelope);
        }
        case TelemetryValidationService.Result.Rejected rejected -> {
          dataQualityTracker.recordQuarantined(rejected.reason());
          if (rejected.field() == null) {
            log.warn("mqtt_telemetry_rejected reason={} traceId={} topic={}",
                rejected.reason(), envelope.traceId(), envelope.topic());
          } else {
            log.warn("mqtt_telemetry_rejected reason={} field={} traceId={} topic={}",
                rejected.reason(), rejected.field(), envelope.traceId(), envelope.topic());
          }
          quarantineService.quarantine(envelope, rejected);
        }
      }
    } catch (RuntimeException exception) {
      if (validationPassed) {
        dataQualityTracker.recordDeadLettered();
      }
      Object topicHeader = message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
      log.error("mqtt_telemetry_ingest_failed traceId={} topic={}",
          traceId == null ? "" : traceId, topicHeader == null ? "" : topicHeader, exception);
    }
  }
}
