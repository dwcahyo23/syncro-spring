package com.syncro.telemetry.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.Point;
import com.syncro.telemetry.application.TelemetryEnvelope;
import com.syncro.telemetry.application.TelemetryPayload;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InfluxTelemetryWriter {

  private static final Logger log = LoggerFactory.getLogger(InfluxTelemetryWriter.class);
  private static final int MAX_ATTEMPTS = 3;
  private static final long BACKOFF_BASE_MILLIS = 100;
  private static final Instant INFLUX_MIN_WRITABLE = Instant.parse("1677-09-21T00:12:43.145224192Z");
  private static final Instant INFLUX_MAX_WRITABLE = Instant.parse("2262-04-11T23:47:16.854775807Z");

  private final InfluxDBClient client;

  public InfluxTelemetryWriter(InfluxDBClient client) {
    this.client = client;
  }

  public static Point toPoint(TelemetryPayload payload, TelemetryEnvelope envelope, String plantCode,
      String machineCode, long countingDelta) {
    var point = Point.measurement("telemetry")
        .setTag("plantCode", plantCode)
        .setTag("machineCode", machineCode)
        .setField("running", payload.running())
        .setField("runtimeHours", payload.runtimeHours())
        .setField("counting", payload.counting())
        .setField("countingDelta", countingDelta)
        .setField("traceId", envelope.traceId());
    for (var entry : payload.optionalFields().entrySet()) {
      addOptionalField(point, entry.getKey(), entry.getValue());
    }
    Instant pointTime = payload.timestamp();
    if (pointTime == null || pointTime.isBefore(INFLUX_MIN_WRITABLE) || pointTime.isAfter(INFLUX_MAX_WRITABLE)) {
      pointTime = envelope.receivedAt();
      point.setField("timestampInferred", true);
    }
    return point.setTimestamp(pointTime);
  }

  private static void addOptionalField(Point point, String name, JsonNode node) {
    if (node.isNumber()) {
      point.setField(name, node.doubleValue());
    } else if (node.isBoolean()) {
      point.setField(name, node.booleanValue());
    } else {
      point.setField(name, node.asText());
    }
  }

  public void write(Point point, String machineCode, String traceId) {
    RuntimeException failure = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        client.writePoint(point);
        log.info("mqtt_telemetry_influx_write machineCode={} traceId={}", machineCode, traceId);
        return;
      } catch (Exception exception) {
        RuntimeException rte = (exception instanceof RuntimeException re) ? re
            : new RuntimeException(exception);
        if (!isTransient(exception)) {
          throw rte;
        }
        failure = rte;
        if (attempt < MAX_ATTEMPTS) {
          sleep(BACKOFF_BASE_MILLIS * attempt, rte);
        }
      }
    }
    throw failure;
  }

  private static boolean isTransient(Exception exception) {
    String msg = exception.getMessage();
    if (msg == null) {
      return true;
    }
    // Treat HTTP 429 and 5xx as transient
    return msg.contains("429") || msg.contains("5");
  }

  private static void sleep(long millis, RuntimeException pendingFailure) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw pendingFailure;
    }
  }
}
