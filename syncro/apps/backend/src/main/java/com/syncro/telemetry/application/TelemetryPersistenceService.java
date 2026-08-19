package com.syncro.telemetry.application;

import com.influxdb.client.write.Point;
import com.syncro.config.TelemetryProperties;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.telemetry.infrastructure.InfluxTelemetryWriter;
import com.syncro.telemetry.infrastructure.MachineCounterStateEntity;
import com.syncro.telemetry.infrastructure.MachineCounterStateRepository;
import com.syncro.telemetry.infrastructure.RedisLatestTelemetryWriter;
import com.syncro.alert.application.SparepartAlertService;
import com.syncro.sparepart.application.SparepartLifetimeEvaluator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
  private final SparepartLifetimeEvaluator evaluator;
  private final SparepartAlertService alertService;
  private final MachineCounterStateRepository counterStateRepo;

  public TelemetryPersistenceService(MachineRepository machines, InfluxTelemetryWriter influxWriter,
      RedisLatestTelemetryWriter redisLatestWriter, StringRedisTemplate redis, TelemetryProperties properties,
      SparepartLifetimeEvaluator evaluator, SparepartAlertService alertService,
      MachineCounterStateRepository counterStateRepo) {
    this.machines = machines;
    this.influxWriter = influxWriter;
    this.redisLatestWriter = redisLatestWriter;
    this.redis = redis;
    this.properties = properties;
    this.evaluator = evaluator;
    this.alertService = alertService;
    this.counterStateRepo = counterStateRepo;
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

    String dedupeKey = "syncro:machine:" + machineId + ":telemetry:dedupe:" + accepted.payload().messageId();
    Boolean acquired = redis.opsForValue().setIfAbsent(dedupeKey, envelope.traceId(), properties.dedupeWindow());
    if (acquired == null) {
      throw new IllegalStateException("dedupe gate unavailable for machine " + machineCode);
    }
    if (!acquired) {
      String winnerTraceId = redis.opsForValue().get(dedupeKey);
      log.warn("mqtt_telemetry_duplicate traceId={} machineCode={} messageId={} winnerTraceId={}",
          envelope.traceId(), machineCode, accepted.payload().messageId(),
          winnerTraceId == null ? "unknown" : winnerTraceId);
      return;
    }

    long countingDelta;
    try {
      long previousCounting = redisLatestWriter.readCounting(machineId)
          .or(() -> counterStateRepo.findById(machineId).map(MachineCounterStateEntity::getCounting))
          .orElse(-1L);
      countingDelta = (previousCounting < 0) ? 0L
          : CountingDeltaCalculator.delta(previousCounting, accepted.payload().counting());
      Point point = InfluxTelemetryWriter.toPoint(accepted.payload(), envelope, plantCode, machineCode, countingDelta);
      influxWriter.write(point, machineCode, envelope.traceId());
    } catch (RuntimeException exception) {
      deleteDedupeKey(dedupeKey, machineCode, envelope.traceId());
      throw exception;
    }

    long currentCounting = accepted.payload().counting();
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
    try {
      Map<String, String> existingHash = redisLatestWriter.readLatestAsMap(machineId);
      Set<String> currentOptionalKeys = accepted.payload().optionalFields().keySet().stream()
          .map(k -> "optional." + k)
          .collect(Collectors.toSet());
      List<String> staleOptionalKeys = existingHash.keySet().stream()
          .filter(k -> k.startsWith("optional.") && !currentOptionalKeys.contains(k))
          .toList();
      redisLatestWriter.hdel(machineId, staleOptionalKeys);
    } catch (RuntimeException hdelEx) {
      log.warn("redis_hdel_optional_failed machineId={} traceId={}", machineId, envelope.traceId(), hdelEx);
    }
    try {
      redisLatestWriter.putLatest(machineId, latest, properties.latestTtl());
    } catch (RuntimeException redisEx) {
      log.warn("redis_latest_write_failed_baseline_may_be_stale machineId={} traceId={} counting={}",
          machineId, envelope.traceId(), currentCounting, redisEx);
      try {
        counterStateRepo.save(new MachineCounterStateEntity(machineId, currentCounting));
      } catch (RuntimeException dbEx) {
        log.warn("counter_state_compensating_write_failed machineId={} traceId={}",
            machineId, envelope.traceId(), dbEx);
      }
      throw redisEx;
    }
    counterStateRepo.save(new MachineCounterStateEntity(machineId, currentCounting));

    try {
      var results = evaluator.evaluateAll(machineId);
      alertService.evaluateAndCreateAlerts(machineId, results, envelope.traceId());
    } catch (Exception e) {
      log.warn("[traceId={}] SparepartLifetimeEvaluator.evaluateAll or alert creation failed, skipping", envelope.traceId(), e);
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
