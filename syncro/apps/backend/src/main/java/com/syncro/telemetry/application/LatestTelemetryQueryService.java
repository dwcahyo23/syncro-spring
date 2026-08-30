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
      // DW-70: return a marker entry so consumers can distinguish a Redis outage from a
      // machine that never sent telemetry (empty hash).
      return new LatestTelemetryDto.TelemetryData(machineId, false, null, null, null,
          LatestTelemetryDto.FreshnessState.OFFLINE, Map.of(), false, true);
    }
    return parseTelemetryData(machineId, manualStatus, redisData);
  }

  /**
   * Batch variant of {@link #latestTelemetry} used by the machine LIST endpoint: reads all
   * latest hashes in one pipelined Redis round trip and parses each with the identical
   * per-machine semantics as the single path.
   *
   * <p>A batch-level Redis failure degrades the WHOLE page with one warning (every entry
   * absent) instead of N sequential failures; per-machine absence/malformation still only
   * affects that machine.
   *
   * @param machineStatuses machineId -> manual status for freshness calculation
   * @return parsed telemetry keyed by machineId; machines without readable data are absent
   */
  public Map<UUID, LatestTelemetryDto.TelemetryData> latestTelemetryBatch(Map<UUID, MachineStatus> machineStatuses) {
    if (machineStatuses == null || machineStatuses.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Map<String, String>> batchData;
    try {
      batchData = redisWriter.readLatestBatch(machineStatuses.keySet());
    } catch (RuntimeException redisFailure) {
      var sampleIds = machineStatuses.keySet().stream().limit(5).map(UUID::toString).toList();
      log.warn("telemetry_latest_batch_read_failed machines={} sampleIds={}",
          machineStatuses.size(), sampleIds, redisFailure);
      return Map.of();
    }
    Map<UUID, LatestTelemetryDto.TelemetryData> result = new LinkedHashMap<>();
    for (Map.Entry<UUID, MachineStatus> entry : machineStatuses.entrySet()) {
      var parsed = parseTelemetryData(entry.getKey(), entry.getValue(),
          batchData.getOrDefault(entry.getKey(), Map.of()));
      if (parsed != null) {
        result.put(entry.getKey(), parsed);
      }
    }
    return result;
  }

  private LatestTelemetryDto.TelemetryData parseTelemetryData(UUID machineId, MachineStatus manualStatus,
      Map<String, String> redisData) {
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
        !optionalFields.isEmpty(),
        false);
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
