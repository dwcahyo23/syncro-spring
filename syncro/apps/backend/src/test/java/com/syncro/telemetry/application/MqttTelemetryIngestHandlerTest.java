package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.support.MessageBuilder;

class MqttTelemetryIngestHandlerTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-08-08T10:00:00Z");

  private final TelemetryPersistenceService persistence = mock(TelemetryPersistenceService.class);
  private final TelemetryQuarantineService quarantine = mock(TelemetryQuarantineService.class);

  private final MqttTelemetryIngestHandler handler =
      new MqttTelemetryIngestHandler(Clock.fixed(FIXED_NOW, ZoneOffset.UTC), new AcceptingTelemetryValidationService(),
          persistence, quarantine);

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
  void handleMessageLogsReceivedForAcceptedMessage() {
    var appender = attachAppender();
    var message = MessageBuilder.withPayload("{\"running\":true}".getBytes(StandardCharsets.UTF_8))
        .setHeader(MqttHeaders.RECEIVED_TOPIC, "factory/GM1/BF-08410/telemetry")
        .build();

    handler.handleMessage(message);

    assertThat(appender.list)
        .anyMatch(event -> event.getLevel() == Level.INFO
            && event.getFormattedMessage().startsWith("mqtt_telemetry_received")
            && event.getFormattedMessage().contains("topic=factory/GM1/BF-08410/telemetry")
            && event.getFormattedMessage().contains("traceId="));
    detachAppender(appender);
  }

  @Test
  void handleMessageLogsRejectionWithReasonAndTraceId() {
    var rejectingHandler = new MqttTelemetryIngestHandler(Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
        new RejectingTelemetryValidationService(), persistence, quarantine);
    var appender = attachAppender();
    var message = MessageBuilder.withPayload("{\"running\":true}".getBytes(StandardCharsets.UTF_8))
        .setHeader(MqttHeaders.RECEIVED_TOPIC, "factory/GM1/BF-08410/telemetry")
        .build();

    rejectingHandler.handleMessage(message);

    assertThat(appender.list)
        .anyMatch(event -> event.getLevel() == Level.WARN
            && event.getFormattedMessage().startsWith("mqtt_telemetry_rejected")
            && event.getFormattedMessage().contains("reason=unknown_machine")
            && event.getFormattedMessage().contains("traceId="));
    detachAppender(appender);
  }

  @Test
  void handleMessageLogsInactiveMachineRejectionWithReasonTraceIdAndTopic() {
    var inactiveHandler = new MqttTelemetryIngestHandler(Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
        new RejectingTelemetryValidationService("inactive_machine"), persistence, quarantine);
    var appender = attachAppender();
    var message = MessageBuilder.withPayload("{\"running\":true}".getBytes(StandardCharsets.UTF_8))
        .setHeader(MqttHeaders.RECEIVED_TOPIC, "factory/GM1/BF-08410/telemetry")
        .build();

    inactiveHandler.handleMessage(message);

    assertThat(appender.list)
        .anyMatch(event -> event.getLevel() == Level.WARN
            && event.getFormattedMessage().startsWith("mqtt_telemetry_rejected")
            && event.getFormattedMessage().contains("reason=inactive_machine")
            && !event.getFormattedMessage().contains("field=")
            && event.getFormattedMessage().contains("traceId=")
            && event.getFormattedMessage().contains("topic=factory/GM1/BF-08410/telemetry"));
    detachAppender(appender);
  }

  @Test
  void handleMessageSwallowsRejectionWithoutThrowing() {
    var rejectingHandler = new MqttTelemetryIngestHandler(Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
        new RejectingTelemetryValidationService(), persistence, quarantine);
    var message = MessageBuilder.withPayload("{\"running\":true}".getBytes(StandardCharsets.UTF_8))
        .setHeader(MqttHeaders.RECEIVED_TOPIC, "factory/GM1/BF-08410/telemetry")
        .build();

    assertThatCode(() -> rejectingHandler.handleMessage(message)).doesNotThrowAnyException();
  }

  @Test
  void persistIsInvokedOnAcceptedMessage() {
    var message = MessageBuilder.withPayload("{\"running\":true}".getBytes(StandardCharsets.UTF_8))
        .setHeader(MqttHeaders.RECEIVED_TOPIC, "factory/GM1/BF-08410/telemetry")
        .build();

    handler.handleMessage(message);

    verify(persistence).persist(any(), any());
  }

  @Test
  void persistIsNotInvokedOnRejectedMessage() {
    var rejectingHandler = new MqttTelemetryIngestHandler(Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
        new RejectingTelemetryValidationService(), persistence, quarantine);
    var message = MessageBuilder.withPayload("{\"running\":true}".getBytes(StandardCharsets.UTF_8))
        .setHeader(MqttHeaders.RECEIVED_TOPIC, "factory/GM1/BF-08410/telemetry")
        .build();

    rejectingHandler.handleMessage(message);

    verify(persistence, never()).persist(any(), any());
  }

  @Test
  void handleMessageSwallowsPersistenceFailureWithoutThrowing() {
    doThrow(new RuntimeException("persistence down")).when(persistence).persist(any(), any());
    var message = MessageBuilder.withPayload("{\"running\":true}".getBytes(StandardCharsets.UTF_8))
        .setHeader(MqttHeaders.RECEIVED_TOPIC, "factory/GM1/BF-08410/telemetry")
        .build();

    assertThatCode(() -> handler.handleMessage(message)).doesNotThrowAnyException();
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

  private static ListAppender<ILoggingEvent> attachAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(MqttTelemetryIngestHandler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    Logger logger = (Logger) LoggerFactory.getLogger(MqttTelemetryIngestHandler.class);
    logger.detachAppender(appender);
    appender.stop();
  }
}
