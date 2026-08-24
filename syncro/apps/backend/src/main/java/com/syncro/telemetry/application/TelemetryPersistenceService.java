package com.syncro.telemetry.application;

import com.influxdb.v3.client.Point;
import com.syncro.config.TelemetryProperties;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.telemetry.infrastructure.InfluxTelemetryWriter;
import com.syncro.telemetry.infrastructure.MachineCounterStateEntity;
import com.syncro.telemetry.infrastructure.MachineCounterStateRepository;
import com.syncro.telemetry.infrastructure.RedisLatestTelemetryWriter;
import com.syncro.alert.application.SparepartAlertService;
import com.syncro.sparepart.application.SparepartLifetimeEvaluator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
  private final Clock clock;
  private final TelemetryDataQualityTracker dataQualityTracker;
  private final PerMachineExecution perMachineExecution;
  private final org.springframework.context.ApplicationEventPublisher events;

  public TelemetryPersistenceService(MachineRepository machines, InfluxTelemetryWriter influxWriter,
      RedisLatestTelemetryWriter redisLatestWriter, StringRedisTemplate redis, TelemetryProperties properties,
      SparepartLifetimeEvaluator evaluator, SparepartAlertService alertService,
      MachineCounterStateRepository counterStateRepo, Clock clock,
      TelemetryDataQualityTracker dataQualityTracker, PerMachineExecution perMachineExecution,
      org.springframework.context.ApplicationEventPublisher events) {
    this.machines = machines;
    this.influxWriter = influxWriter;
    this.redisLatestWriter = redisLatestWriter;
    this.redis = redis;
    this.properties = properties;
    this.evaluator = evaluator;
    this.alertService = alertService;
    this.counterStateRepo = counterStateRepo;
    this.clock = clock;
    this.dataQualityTracker = dataQualityTracker;
    this.perMachineExecution = perMachineExecution;
    this.events = events;
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

    // Per-machine counting delta (readCounting -> delta -> Influx write -> putLatest -> counter-state
    // save) is read-modify-write state: serializing it per machine prevents two concurrent messages for
    // the same machine from reading the same baseline and double-counting. Different machines take
    // different stripes and still run in parallel. The dedupe SETNX gate above stays outside the lock
    // (per-messageId, idempotent); alert evaluation stays outside too.
    final long[] countingDeltaHolder = new long[1];
    perMachineExecution.run(machineId, () -> {
      long previousCounting;
      try {
        previousCounting = redisLatestWriter.readCounting(machineId)
            .or(() -> counterStateRepo.findById(machineId).map(MachineCounterStateEntity::getCounting))
            .orElse(-1L);
        countingDeltaHolder[0] = (previousCounting < 0) ? 0L
            : CountingDeltaCalculator.delta(previousCounting, accepted.payload().counting());
        Point point = InfluxTelemetryWriter.toPoint(accepted.payload(), envelope, plantCode, machineCode,
            countingDeltaHolder[0]);
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
      latest.put("countingDelta", Long.toString(countingDeltaHolder[0]));
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
        recordLatencySafely(accepted.payload().timestamp());
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
    });
    long countingDelta = countingDeltaHolder[0];
    // Accepted telemetry is the fastest-changing input to story 8-6 projections; evict the
    // machine's cached estimate so a fresh GET reflects the new sample immediately.
    events.publishEvent(new com.syncro.projection.application.ProjectionCacheEvictionEvent(machineId));

    try {
      var results = evaluator.evaluateAll(machineId);
      try {
        alertService.evaluateAndCreateAlerts(machineId, results, envelope.traceId());
      } catch (Exception alertEx) {
        log.warn("sparepart_alert_evaluation_failed traceId={} machineId={}",
            envelope.traceId(), machineId, alertEx);
      }
    } catch (Exception evalEx) {
      log.warn("sparepart_lifetime_evaluation_failed traceId={} machineId={}",
          envelope.traceId(), machineId, evalEx);
    }

    log.info("mqtt_telemetry_persisted traceId={} machineCode={} countingDelta={}",
        envelope.traceId(), machineCode, countingDelta);
  }

  /**
   * Publish-to-visible latency: payload publish timestamp → latest telemetry now queryable.
   * Recorded only after the latest write succeeds, so duplicates (early return) and failures
   * never sample. An implausible device timestamp beyond the Duration millis range skips the
   * sample instead of masquerading as a Redis failure (ArithmeticException must not reach the
   * caller's redis-catch here).
   */
  private void recordLatencySafely(Instant publishedAt) {
    try {
      dataQualityTracker.recordLatencyMs(
          Duration.between(publishedAt, Instant.now(clock)).toMillis());
    } catch (ArithmeticException durationOverflow) {
      log.warn("telemetry_latency_sample_skipped_implausible_timestamp publishedAt={}", publishedAt);
    }
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
