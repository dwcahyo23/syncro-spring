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

  public MqttTelemetryIngestHandler(Clock clock, TelemetryValidationService validationService) {
    this.clock = clock;
    this.validationService = validationService;
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
    try {
      TelemetryEnvelope envelope = enrich(message);
      switch (validationService.validate(envelope.topic(), envelope.payload())) {
        case TelemetryValidationService.Result.Accepted ignored -> log.info(
            "mqtt_telemetry_received traceId={} topic={} payload={}",
            envelope.traceId(), envelope.topic(), envelope.payload());
        case TelemetryValidationService.Result.Rejected rejected -> {
          if (rejected.field() == null) {
            log.warn("mqtt_telemetry_rejected reason={} traceId={} topic={}",
                rejected.reason(), envelope.traceId(), envelope.topic());
          } else {
            log.warn("mqtt_telemetry_rejected reason={} field={} traceId={} topic={}",
                rejected.reason(), rejected.field(), envelope.traceId(), envelope.topic());
          }
        }
      }
    } catch (RuntimeException exception) {
      Object topicHeader = message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
      log.error("mqtt_telemetry_ingest_failed topic={}", topicHeader == null ? "" : topicHeader, exception);
    }
  }
}
