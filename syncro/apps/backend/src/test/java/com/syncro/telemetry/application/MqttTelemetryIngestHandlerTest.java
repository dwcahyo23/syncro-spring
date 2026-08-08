package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.support.MessageBuilder;

class MqttTelemetryIngestHandlerTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-08-08T10:00:00Z");

  private final MqttTelemetryIngestHandler handler =
      new MqttTelemetryIngestHandler(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

  @Test
  void enrichAssignsTraceIdAndCapturesTopicAndPayload() {
    var message = MessageBuilder.withPayload("{\"running\":true}".getBytes(StandardCharsets.UTF_8))
        .setHeader(MqttHeaders.RECEIVED_TOPIC, "factory/GM1/BF-08410/telemetry")
        .build();

    var envelope = handler.enrich(message);

    assertThat(envelope.traceId()).isNotBlank();
    assertThat(envelope.topic()).isEqualTo("factory/GM1/BF-08410/telemetry");
    assertThat(envelope.payload()).isEqualTo("{\"running\":true}");
    assertThat(envelope.receivedAt()).isEqualTo(FIXED_NOW);
  }

  @Test
  void enrichToleratesEmptyPayload() {
    var message = MessageBuilder.withPayload(new byte[0])
        .setHeader(MqttHeaders.RECEIVED_TOPIC, "factory/GM1/BF-08410/telemetry")
        .build();

    var envelope = handler.enrich(message);

    assertThat(envelope.traceId()).isNotBlank();
    assertThat(envelope.payload()).isEmpty();
    assertThat(envelope.receivedAt()).isNotNull();
  }

  @Test
  void handleMessageDoesNotThrowForInboundMessage() {
    var message = MessageBuilder.withPayload("{}".getBytes(StandardCharsets.UTF_8))
        .setHeader(MqttHeaders.RECEIVED_TOPIC, "factory/GM1/BF-08410/telemetry")
        .build();

    handler.handleMessage(message);
  }

  @Test
  void enrichToleratesMissingTopicHeader() {
    var message = MessageBuilder.withPayload("{}".getBytes(StandardCharsets.UTF_8)).build();

    var envelope = handler.enrich(message);

    assertThat(envelope.traceId()).isNotBlank();
    assertThat(envelope.topic()).isEmpty();
  }

  @Test
  void enrichHandlesStringPayload() {
    var message = MessageBuilder.withPayload("{\"running\":true}")
        .setHeader(MqttHeaders.RECEIVED_TOPIC, "factory/GM1/BF-08410/telemetry")
        .build();

    var envelope = handler.enrich(message);

    assertThat(envelope.payload()).isEqualTo("{\"running\":true}");
    assertThat(envelope.topic()).isEqualTo("factory/GM1/BF-08410/telemetry");
  }
}
