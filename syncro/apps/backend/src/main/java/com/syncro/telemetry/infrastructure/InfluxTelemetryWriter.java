package com.syncro.telemetry.infrastructure;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.exceptions.InfluxException;
import com.syncro.telemetry.application.TelemetryEnvelope;
import com.syncro.telemetry.application.TelemetryPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InfluxTelemetryWriter {

  private static final Logger log = LoggerFactory.getLogger(InfluxTelemetryWriter.class);
  private static final int MAX_ATTEMPTS = 3;
  private static final long BACKOFF_BASE_MILLIS = 100;

  private final WriteApiBlocking writeApi;

  public InfluxTelemetryWriter(InfluxDBClient client) {
    this.writeApi = client.getWriteApiBlocking();
  }

  public static Point toPoint(TelemetryPayload payload, TelemetryEnvelope envelope, String plantCode, String machineCode) {
    return Point.measurement("telemetry")
        .addTag("plantCode", plantCode)
        .addTag("machineCode", machineCode)
        .addField("running", payload.running())
        .addField("runtimeHours", payload.runtimeHours())
        .addField("counting", payload.counting())
        .addField("traceId", envelope.traceId())
        .time(envelope.receivedAt().getEpochSecond() * 1_000_000_000L + envelope.receivedAt().getNano(),
            WritePrecision.NS);
  }

  public void write(Point point, String machineCode, String traceId) {
    RuntimeException failure = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        writeApi.writePoint(point);
        log.info("mqtt_telemetry_influx_write machineCode={} traceId={}", machineCode, traceId);
        return;
      } catch (InfluxException exception) {
        if (!isTransient(exception)) {
          throw exception;
        }
        failure = exception;
        if (attempt < MAX_ATTEMPTS) {
          sleep(BACKOFF_BASE_MILLIS * attempt, exception);
        }
      } catch (RuntimeException exception) {
        failure = exception;
        if (attempt < MAX_ATTEMPTS) {
          sleep(BACKOFF_BASE_MILLIS * attempt, exception);
        }
      }
    }
    throw failure;
  }

  private static boolean isTransient(InfluxException exception) {
    int status = exception.status();
    return status == 0 || status == 429 || status >= 500;
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
