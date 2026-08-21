package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.support.MessageBuilder;

// Story 3.1 ATDD RED-phase scaffold (R-009: no inbound validation).
//
// The Implementation already satisfies the happy path (see
// MqttTelemetryIngestHandlerTest). These scaffolds are edge-case acceptance
// locks for the R-009 contract "handler never throws to the adapter; malformed
// payload still gets a traceId". They stay @Disabled until a developer activates
// the current task. Removing @Disabled makes the test active: it must FAIL if the
// handler regresses (e.g. a non-byte payload is dropped, a missing topic header
// throws, or an enrichment failure escapes to the adapter).
//
// Run targeted:
//   $env:JAVA_HOME="C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
//   mvn -q -f syncro/apps/backend/pom.xml test -Dtest="MqttTelemetryIngestAtddScaffoldTest"
@Disabled("ATDD RED phase - activate one test at a time during implementation")
class MqttTelemetryIngestAtddScaffoldTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-08-08T10:00:00Z");

  private final MqttTelemetryIngestHandler handler =
      new MqttTelemetryIngestHandler(Clock.fixed(FIXED_NOW, ZoneOffset.UTC), new AcceptingTelemetryValidationService(),
          mock(TelemetryPersistenceService.class), mock(TelemetryQuarantineService.class),
          new TelemetryIngestTracker(Clock.fixed(FIXED_NOW, ZoneOffset.UTC)));

  @Test
  void enrichToleratesNonByteStringPayload() {
    var message = MessageBuilder.withPayload("{\"running\":true}")
        .setHeader(MqttHeaders.RECEIVED_TOPIC, "factory/GM1/BF-08410/telemetry")
        .build();

    var envelope = handler.enrich(message);

    assertThat(envelope.traceId()).isNotBlank();
    assertThat(envelope.payload()).isEqualTo("{\"running\":true}");
    assertThat(envelope.receivedAt()).isEqualTo(FIXED_NOW);
  }

  @Test
  void enrichToleratesMissingTopicHeader() {
    var message = MessageBuilder.withPayload("{}".getBytes()).build();

    var envelope = handler.enrich(message);

    assertThat(envelope.traceId()).isNotBlank();
    assertThat(envelope.topic()).isEmpty();
    assertThat(envelope.receivedAt()).isEqualTo(FIXED_NOW);
  }

  @Test
  void handleMessageSwallowsEnrichmentFailureToAdapter() {
    // A payload whose toString() throws forces enrich() to throw; the handler
    // must swallow it (log mqtt_telemetry_ingest_failed) and never rethrow to
    // the Spring Integration adapter (R-009).
    Object throwingPayload = new Object() {
      @Override
      public String toString() {
        throw new IllegalStateException("boom");
      }
    };
    var message = MessageBuilder.withPayload(throwingPayload)
        .setHeader(MqttHeaders.RECEIVED_TOPIC, "factory/GM1/BF-08410/telemetry")
        .build();

    assertThatCode(() -> handler.handleMessage(message)).doesNotThrowAnyException();
  }
}