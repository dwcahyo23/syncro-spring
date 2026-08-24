package com.syncro.projection.infrastructure;

import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.PointValues;
import com.influxdb.v3.client.internal.GrpcCallOptions;
import com.influxdb.v3.client.query.QueryOptions;
import com.syncro.config.InfluxProperties;
import io.grpc.Deadline;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InfluxTelemetryHistoryReader {

  private static final Logger log = LoggerFactory.getLogger(InfluxTelemetryHistoryReader.class);

  /** Bounded total query time, mirroring the Garage client's fixed call-timeout precedent. */
  private static final long QUERY_DEADLINE_SECONDS = 10;

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private static final String SAMPLE_SQL = """
      SELECT * FROM telemetry
      WHERE "machineCode" = '%s' AND time >= '%s' AND time <= '%s'
      ORDER BY time ASC
      """;

  private final InfluxDBClient client;
  private final InfluxProperties properties;

  public InfluxTelemetryHistoryReader(InfluxDBClient client, InfluxProperties properties) {
    this.client = client;
    this.properties = properties;
  }

  public record TelemetrySample(Instant timestamp, long counting) {
  }

  public static class HistoryReadException extends RuntimeException {
    public HistoryReadException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Accepted-telemetry counting samples for one machine ordered by point time. Uses the SQL
   * idiom proven in {@code TelemetryPersistenceIntegrationTest}: the v3 {@code queryPoints}
   * call accepts only literal SQL (no bind parameters), so the tag literal is single-quote
   * escaped and time bounds come from {@code Instant} values, never raw user input. Failures
   * are wrapped in {@link HistoryReadException} so callers degrade to an explicit unavailable
   * state instead of propagating a 500.
   */
  public List<TelemetrySample> readSamples(String machineCode, Instant from, Instant to) {
    String sql = SAMPLE_SQL.formatted(escapeLiteral(machineCode), from, to);
    try (var stream = client.queryPoints(sql, options())) {
      List<TelemetrySample> samples = new ArrayList<>();
      stream.forEach(point -> addSample(samples, point));
      return List.copyOf(samples);
    } catch (Exception exception) {
      throw new HistoryReadException(
          "telemetry history read failed for machineCode=" + machineCode, exception);
    }
  }

  private static String escapeLiteral(String value) {
    return value.replace("'", "''");
  }

  private QueryOptions options() {
    var options = new QueryOptions(properties.database());
    options.setGrpcCallOptions(new GrpcCallOptions.Builder()
        .withDeadline(Deadline.after(QUERY_DEADLINE_SECONDS, TimeUnit.SECONDS))
        .build());
    return options;
  }

  private static void addSample(List<TelemetrySample> samples, PointValues point) {
    Object counting = point.getField("counting");
    Number timestampNanos = point.getTimestamp();
    if (!(counting instanceof Number countingValue) || timestampNanos == null) {
      log.warn("projection_history_sample_malformed skippedPoint={}", point);
      return;
    }
    long nanos = timestampNanos.longValue();
    samples.add(new TelemetrySample(
        Instant.ofEpochSecond(nanos / NANOS_PER_SECOND, nanos % NANOS_PER_SECOND),
        countingValue.longValue()));
  }
}
