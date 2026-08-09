package com.syncro.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influxdb.client.domain.WritePrecision;
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
    var payload = new TelemetryPayload(true, 12.5, 100);
    var envelope = new TelemetryEnvelope(traceId, "factory/GM1/BF-08410/telemetry", "{}", receivedAt);

    var point = InfluxTelemetryWriter.toPoint(payload, envelope, "GM1", "BF-08410", 0);

    assertThat(point.toLineProtocol())
        .startsWith("telemetry,machineCode=BF-08410,plantCode=GM1 ")
        .contains("counting=100i")
        .contains("countingDelta=0i")
        .contains("running=true")
        .contains("runtimeHours=12.5")
        .contains("traceId=\"trace-123\"");
    assertThat(point.getTime()).isEqualTo(receivedAt.getEpochSecond() * 1_000_000_000L + receivedAt.getNano());
    assertThat(point.getPrecision()).isEqualTo(WritePrecision.NS);
  }

  @Test
  void countingIsStoredAsLongField() {
    var payload = new TelemetryPayload(false, 3.5, 42);
    var envelope = new TelemetryEnvelope("trace-456", "factory/GM1/BF-08410/telemetry", "{}",
        Instant.parse("2026-08-08T10:00:00Z"));

    var point = InfluxTelemetryWriter.toPoint(payload, envelope, "GM1", "BF-08410", 7);

    assertThat(point.toLineProtocol())
        .contains("counting=42i")
        .contains("countingDelta=7i");
  }

  @Test
  void handlesBoundaryFieldValues() {
    var payload = new TelemetryPayload(true, 1.0E308, Long.MAX_VALUE);
    var envelope = new TelemetryEnvelope("trace-789", "factory/GM1/BF-08410/telemetry", "{}",
        Instant.parse("2026-08-08T10:00:00Z"));

    String lineProtocol = InfluxTelemetryWriter.toPoint(payload, envelope, "GM1", "BF-08410", 0).toLineProtocol();

    assertThat(lineProtocol)
        .contains("counting=" + Long.MAX_VALUE + "i")
        .contains("countingDelta=0i")
        .contains("running=true")
        .contains("runtimeHours=");
    assertThat(Double.parseDouble(fieldValue(lineProtocol, "runtimeHours"))).isEqualTo(1.0E308);
    assertThat(Long.parseLong(fieldValue(lineProtocol, "counting").replaceFirst("i$", ""))).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void writesOptionalFieldsWithInferredTypes() {
    var parsed = (TelemetryPayload.ParseResult.Accepted) TelemetryPayload.parse(
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4,\"rpm\":1200,\"heaterOn\":true,\"qualityGrade\":\"A\"}",
        new ObjectMapper(), Set.of("vibration", "rpm", "heaterOn", "qualityGrade"));
    var envelope = new TelemetryEnvelope("trace-opt-1", "factory/GM1/BF-08410/telemetry", "{}",
        Instant.parse("2026-08-08T10:00:00Z"));

    String lineProtocol = InfluxTelemetryWriter.toPoint(parsed.payload(), envelope, "GM1", "BF-08410", 0)
        .toLineProtocol();

    assertThat(lineProtocol)
        .contains("vibration=2.4")
        .contains("rpm=1200i")
        .contains("heaterOn=true")
        .contains("qualityGrade=\"A\"");
  }

  private static String fieldValue(String lineProtocol, String field) {
    String marker = field + "=";
    int start = lineProtocol.indexOf(marker) + marker.length();
    int end = lineProtocol.indexOf(',', start);
    return end == -1 ? lineProtocol.substring(start) : lineProtocol.substring(start, end);
  }
}
