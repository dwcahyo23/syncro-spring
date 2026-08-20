package com.syncro.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.telemetry.application.TelemetryEnvelope;
import com.syncro.telemetry.application.TelemetryPayload;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InfluxTelemetryWriterTest {

  @Test
  void buildsPointWithCanonicalSchema() {
    String traceId = "trace-123";
    Instant receivedAt = Instant.parse("2026-08-08T10:00:00Z");
    Instant payloadTimestamp = Instant.parse("2026-08-08T10:00:05Z");
    var payload = new TelemetryPayload(true, 12.5, 100, "1.0", "m-1", payloadTimestamp);
    var envelope = new TelemetryEnvelope(traceId, "factory/GM1/BF-08410/telemetry", "{}", receivedAt);

    var point = InfluxTelemetryWriter.toPoint(payload, envelope, "GM1", "BF-08410", 0);
    String lp = point.toLineProtocol();

    assertThat(lp)
        .startsWith("telemetry,machineCode=BF-08410,plantCode=GM1 ")
        .contains("counting=100i")
        .contains("countingDelta=0i")
        .contains("running=true")
        .contains("runtimeHours=12.5")
        .contains("traceId=\"trace-123\"")
        .doesNotContain("timestampInferred=true");
    // timestamp at end of line protocol is nanosecond epoch
    long expectedNanos = payloadTimestamp.getEpochSecond() * 1_000_000_000L + payloadTimestamp.getNano();
    assertThat(lp).endsWith(" " + expectedNanos);
  }

  @Test
  void fallsBackToReceivedAtAndFlagsWhenPayloadTimestampAbsent() {
    String traceId = "trace-abs";
    Instant receivedAt = Instant.parse("2026-08-08T10:00:00Z");
    var payload = new TelemetryPayload(true, 12.5, 100, "1.0", "m-2", null);
    var envelope = new TelemetryEnvelope(traceId, "factory/GM1/BF-08410/telemetry", "{}", receivedAt);

    var point = InfluxTelemetryWriter.toPoint(payload, envelope, "GM1", "BF-08410", 0);
    String lp = point.toLineProtocol();

    assertThat(lp).contains("timestampInferred=true");
    long expectedNanos = receivedAt.getEpochSecond() * 1_000_000_000L + receivedAt.getNano();
    assertThat(lp).endsWith(" " + expectedNanos);
  }

  @Test
  void fallsBackToReceivedAtAndFlagsWhenPayloadTimestampOutsideWritableRange() {
    String traceId = "trace-range";
    Instant receivedAt = Instant.parse("2026-08-08T10:00:00Z");
    var tooOld = new TelemetryPayload(true, 12.5, 100, "1.0", "m-old", Instant.parse("1600-01-01T00:00:00Z"));
    var envelope = new TelemetryEnvelope(traceId, "factory/GM1/BF-08410/telemetry", "{}", receivedAt);

    var point = InfluxTelemetryWriter.toPoint(tooOld, envelope, "GM1", "BF-08410", 0);
    String lp = point.toLineProtocol();

    assertThat(lp).contains("timestampInferred=true");
    long expectedNanos = receivedAt.getEpochSecond() * 1_000_000_000L + receivedAt.getNano();
    assertThat(lp).endsWith(" " + expectedNanos);
  }

  @Test
  void fallsBackWhenPayloadTimestampTooFarInFuture() {
    String traceId = "trace-future";
    Instant receivedAt = Instant.parse("2026-08-08T10:00:00Z");
    var tooNew = new TelemetryPayload(true, 12.5, 100, "1.0", "m-new", Instant.parse("2300-01-01T00:00:00Z"));
    var envelope = new TelemetryEnvelope(traceId, "factory/GM1/BF-08410/telemetry", "{}", receivedAt);

    var point = InfluxTelemetryWriter.toPoint(tooNew, envelope, "GM1", "BF-08410", 0);
    String lp = point.toLineProtocol();

    assertThat(lp).contains("timestampInferred=true");
    long expectedNanos = receivedAt.getEpochSecond() * 1_000_000_000L + receivedAt.getNano();
    assertThat(lp).endsWith(" " + expectedNanos);
  }

  @Test
  void optionalFieldsAreIncludedWithCorrectTypes() throws Exception {
    var parsed = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-opt-1\",\"timestamp\":\"2026-08-08T10:00:05Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4,\"rpm\":1200,"
            + "\"heaterOn\":true,\"qualityGrade\":\"A\"}",
        new ObjectMapper(), Set.of("vibration", "rpm", "heaterOn", "qualityGrade"));
    var envelope = new TelemetryEnvelope("trace-opt-1", "factory/GM1/BF-08410/telemetry", "{}",
        Instant.parse("2026-08-08T10:00:00Z"));

    String lp = InfluxTelemetryWriter.toPoint(((TelemetryPayload.ParseResult.Accepted) parsed).payload(), envelope, "GM1", "BF-08410", 0)
        .toLineProtocol();

    assertThat(lp)
        .contains("vibration=2.4")
        .contains("rpm=1200i")
        .contains("heaterOn=true")
        .contains("qualityGrade=\"A\"");
  }
}
