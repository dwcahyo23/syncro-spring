package com.syncro.telemetry.application;

import com.syncro.machine.domain.MachineStatus;
import com.syncro.telemetry.infrastructure.RedisLatestTelemetryWriter;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LatestTelemetryQueryService {

  private static final Logger log = LoggerFactory.getLogger(LatestTelemetryQueryService.class);
  private static final String OPTIONAL_FIELD_PREFIX = "optional.";

  private final RedisLatestTelemetryWriter redisWriter;
  private final TelemetryFreshnessCalculator freshnessCalculator;

  public LatestTelemetryQueryService(RedisLatestTelemetryWriter redisWriter,
      TelemetryFreshnessCalculator freshnessCalculator) {
    this.redisWriter = redisWriter;
    this.freshnessCalculator = freshnessCalculator;
  }

  public LatestTelemetryDto.TelemetryData latestTelemetry(UUID machineId, MachineStatus manualStatus) {
    Map<String, String> redisData;
    try {
      redisData = redisWriter.readLatestAsMap(machineId);
    } catch (RuntimeException redisFailure) {
      log.warn("telemetry_latest_read_failed machineId={}", machineId, redisFailure);
      return null;
    }
    if (redisData.isEmpty()) {
      return null;
    }

    Instant lastReceivedAt;
    try {
      lastReceivedAt = Instant.parse(redisData.get("receivedAt"));
    } catch (DateTimeParseException | NullPointerException malformed) {
      log.warn("telemetry_latest_malformed machineId={} rawValue={}", machineId, redisData.get("receivedAt"));
      return null;
    }

    boolean running = Boolean.parseBoolean(redisData.getOrDefault("running", "false"));
    Double runtimeHours = parseDouble(redisData.get("runtimeHours"), machineId);
    Long counting = parseLong(redisData.get("counting"), machineId);

    Map<String, String> optionalFields = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : redisData.entrySet()) {
      if (entry.getKey().startsWith(OPTIONAL_FIELD_PREFIX)) {
        optionalFields.put(entry.getKey().substring(OPTIONAL_FIELD_PREFIX.length()), entry.getValue());
      }
    }

    LatestTelemetryDto.FreshnessState freshness = freshnessCalculator.calculate(lastReceivedAt, manualStatus);
    return new LatestTelemetryDto.TelemetryData(
        machineId,
        running,
        runtimeHours,
        counting,
        lastReceivedAt,
        freshness,
        optionalFields,
        !optionalFields.isEmpty());
  }

  private static Double parseDouble(String raw, UUID machineId) {
    if (raw == null) {
      return null;
    }
    try {
      Double value = Double.parseDouble(raw);
      if (value.isNaN() || Double.isInfinite(value)) {
        log.warn("telemetry_latest_runtime_malformed machineId={} rawValue={}", machineId, raw);
        return null;
      }
      return value;
    } catch (NumberFormatException malformed) {
      log.warn("telemetry_latest_runtime_malformed machineId={} rawValue={}", machineId, raw);
      return null;
    }
  }

  private static Long parseLong(String raw, UUID machineId) {
    if (raw == null) {
      return null;
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException malformed) {
      log.warn("telemetry_latest_counting_malformed machineId={} rawValue={}", machineId, raw);
      return null;
    }
  }
}
