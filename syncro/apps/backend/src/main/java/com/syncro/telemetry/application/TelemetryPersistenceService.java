package com.syncro.telemetry.application;

import com.influxdb.client.write.Point;
import com.syncro.config.TelemetryProperties;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.telemetry.infrastructure.InfluxTelemetryWriter;
import com.syncro.telemetry.infrastructure.RedisLatestTelemetryWriter;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class TelemetryPersistenceService {

  private static final Logger log = LoggerFactory.getLogger(TelemetryPersistenceService.class);

  private final MachineRepository machines;
  private final InfluxTelemetryWriter influxWriter;
  private final RedisLatestTelemetryWriter redisLatestWriter;
  private final StringRedisTemplate redis;
  private final TelemetryProperties properties;

  public TelemetryPersistenceService(MachineRepository machines, InfluxTelemetryWriter influxWriter,
      RedisLatestTelemetryWriter redisLatestWriter, StringRedisTemplate redis, TelemetryProperties properties) {
    this.machines = machines;
    this.influxWriter = influxWriter;
    this.redisLatestWriter = redisLatestWriter;
    this.redis = redis;
    this.properties = properties;
  }

  public void persist(TelemetryValidationService.Result.Accepted accepted, TelemetryEnvelope envelope) {
    var machine = machines.findByIdWithPlantAndGroup(accepted.machine().getId())
        .orElseThrow(() -> new IllegalStateException("machine no longer exists: " + accepted.machine().getId()));
    if (machine.getStatus() != MachineStatus.ACTIVE) {
      throw new IllegalStateException("machine is not active: " + machine.getCode());
    }
    String plantCode = machine.getPlant().getCode();
    String machineCode = machine.getCode();
    UUID machineId = machine.getId();

    String dedupeKey = "syncro:machine:" + machineId + ":telemetry:dedupe:" + accepted.payload().running() + ":"
        + accepted.payload().runtimeHours() + ":" + accepted.payload().counting();
    Boolean acquired = redis.opsForValue().setIfAbsent(dedupeKey, envelope.traceId(), properties.dedupeWindow());
    if (acquired == null) {
      throw new IllegalStateException("dedupe gate unavailable for machine " + machineCode);
    }
    if (!acquired) {
      String winnerTraceId = redis.opsForValue().get(dedupeKey);
      log.warn("mqtt_telemetry_duplicate traceId={} machineCode={} counting={} winnerTraceId={}",
          envelope.traceId(), machineCode, accepted.payload().counting(), winnerTraceId == null ? "unknown" : winnerTraceId);
      return;
    }

    long countingDelta;
    try {
      countingDelta = redisLatestWriter.readCounting(machineId)
          .map(previous -> CountingDeltaCalculator.delta(previous, accepted.payload().counting()))
          .orElse(0L);
      Point point = InfluxTelemetryWriter.toPoint(accepted.payload(), envelope, plantCode, machineCode, countingDelta);
      influxWriter.write(point, machineCode, envelope.traceId());
    } catch (RuntimeException exception) {
      deleteDedupeKey(dedupeKey, machineCode, envelope.traceId());
      throw exception;
    }

    try {
      var latest = new LinkedHashMap<String, String>();
      latest.put("machineId", machineId.toString());
      latest.put("machineCode", machineCode);
      latest.put("plantCode", plantCode);
      latest.put("running", Boolean.toString(accepted.payload().running()));
      latest.put("runtimeHours", Double.toString(accepted.payload().runtimeHours()));
      latest.put("counting", Long.toString(accepted.payload().counting()));
      latest.put("countingDelta", Long.toString(countingDelta));
      latest.put("receivedAt", envelope.receivedAt().toString());
      latest.put("traceId", envelope.traceId());
      for (var entry : accepted.payload().optionalFields().entrySet()) {
        latest.put("optional." + entry.getKey(), entry.getValue().asText());
      }
      redisLatestWriter.putLatest(machineId, latest, properties.latestTtl());
    } catch (RuntimeException exception) {
      throw exception;
    }

    log.info("mqtt_telemetry_persisted traceId={} machineCode={} countingDelta={}",
        envelope.traceId(), machineCode, countingDelta);
  }

  private void deleteDedupeKey(String dedupeKey, String machineCode, String traceId) {
    try {
      String winner = redis.opsForValue().get(dedupeKey);
      if (traceId.equals(winner)) {
        redis.delete(dedupeKey);
      }
    } catch (RuntimeException cleanupFailure) {
      log.warn("mqtt_telemetry_dedupe_cleanup_failed machineCode={} traceId={}", machineCode, traceId, cleanupFailure);
    }
  }
}
