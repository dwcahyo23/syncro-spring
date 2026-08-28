package com.syncro.sync.application;

import com.syncro.config.SyncProperties;
import com.syncro.sync.domain.SyncSourceRow;
import com.syncro.sync.infrastructure.SyncRunEntity;
import com.syncro.sync.infrastructure.SyncRunRepository;
import com.syncro.sync.infrastructure.SyncSourceReader;
import com.syncro.sync.infrastructure.SyncWatermarkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled sync poller (AD-7/FR-150, story 13-1). One cycle: acquire the Redis
 * distributed lock → read the watermark → query the external DB in batches ordered
 * by {@code sheet_no ASC} → delegate each batch to {@link SyncBatchProcessor} (which
 * upserts in a transaction and advances the watermark) → record a {@code sync_runs}
 * row. The whole cycle is wrapped in try/catch so an external DB outage (retried
 * with backoff) or any other failure never kills the schedule.
 *
 * <p>Conditional on {@code syncro.sync.enabled=true}: external sync is disabled by
 * default so the app boots without an external DB connection (story 13-1 config note).
 */
@Component
@ConditionalOnProperty(prefix = "syncro.sync", name = "enabled", havingValue = "true")
public class SyncWorker {

  private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);

  static final String LOCK_KEY = "sync:workorder:lock";
  static final String RUNNING = "RUNNING";
  static final String SUCCESS = "SUCCESS";
  static final String FAILED = "FAILED";
  static final int MAX_QUERY_RETRIES = 3;
  static final Duration RETRY_BACKOFF = Duration.ofSeconds(5);

  private final SyncProperties properties;
  private final StringRedisTemplate redis;
  private final SyncSourceReader sourceReader;
  private final SyncWatermarkRepository watermarks;
  private final SyncRunRepository runs;
  private final SyncBatchProcessor batchProcessor;
  private final Clock clock;
  /** Injectable backoff so tests never sleep the real 5s; production uses {@link #RETRY_BACKOFF}. */
  private final java.util.function.Consumer<Duration> backoff;

  @Autowired
  public SyncWorker(SyncProperties properties, StringRedisTemplate redis, SyncSourceReader sourceReader,
      SyncWatermarkRepository watermarks, SyncRunRepository runs, SyncBatchProcessor batchProcessor,
      Clock clock) {
    this(properties, redis, sourceReader, watermarks, runs, batchProcessor, clock, SyncWorker::sleepBackoff);
  }

  /** Package-private constructor for test injection of a no-op backoff. */
  SyncWorker(SyncProperties properties, StringRedisTemplate redis, SyncSourceReader sourceReader,
      SyncWatermarkRepository watermarks, SyncRunRepository runs, SyncBatchProcessor batchProcessor,
      Clock clock, java.util.function.Consumer<Duration> backoff) {
    this.properties = properties;
    this.redis = redis;
    this.sourceReader = sourceReader;
    this.watermarks = watermarks;
    this.runs = runs;
    this.batchProcessor = batchProcessor;
    this.clock = clock;
    this.backoff = backoff;
  }

  @Scheduled(fixedDelayString = "${syncro.sync.poll-interval-ms:60000}")
  public void poll() {
    if (!properties.enabled()) {
      return;
    }
    String instanceId = UUID.randomUUID().toString();
    Boolean acquired = redis.opsForValue().setIfAbsent(
        LOCK_KEY, instanceId, Duration.ofMillis(properties.lockTtlMs()));
    if (!Boolean.TRUE.equals(acquired)) {
      log.info("[SyncWorker] lock {} held by another instance — skipping this cycle", LOCK_KEY);
      return;
    }
    try {
      runCycle(instanceId);
    } catch (Exception e) {
      log.error("[SyncWorker] unexpected failure during cycle — schedule kept alive", e);
    } finally {
      releaseLock(instanceId);
    }
  }

  /**
   * The pipeline body. External DB reads happen before any Syncro DB transaction
   * starts (the external connection is read-only, no transaction needed); each batch
   * is processed transactionally by {@link SyncBatchProcessor}.
   */
  public void runCycle(String instanceId) {
    var runId = UUID.randomUUID();
    var startedAt = Instant.now(clock);
    runs.saveAndFlush(new SyncRunEntity(runId, startedAt, RUNNING, 0, 0, null));

    int totalRead = 0;
    int totalUpserted = 0;
    String lastSheetNo = watermarks.findLastSheetNo().orElse(null);

    try {
      while (true) {
        var batch = readWithRetry(lastSheetNo);
        if (batch.isEmpty()) {
          break;
        }
        totalUpserted += batchProcessor.importBatch(batch).upserted();
        totalRead += batch.size();
        lastSheetNo = lastRowOf(batch);
        if (batch.size() < properties.batchSize()) {
          break;
        }
      }

      var completed = runs.findById(runId).orElseThrow();
      completed.complete(SUCCESS, totalRead, totalUpserted, null, Instant.now(clock));
      runs.saveAndFlush(completed);
      log.info("[SyncWorker] run={} success rowsRead={} rowsUpserted={} watermark={}",
          runId, totalRead, totalUpserted, lastSheetNo);
    } catch (Exception e) {
      var failed = runs.findById(runId).orElseThrow();
      failed.complete(FAILED, totalRead, totalUpserted, safeMessage(e), Instant.now(clock));
      runs.saveAndFlush(failed);
      log.error("[SyncWorker] run={} failed rowsRead={} watermark={}: {}",
          runId, totalRead, lastSheetNo, e.getMessage(), e);
    }
  }

  /**
   * Reads the next batch, retrying up to 3× with 5s backoff when the external DB is
   * unreachable. After the retries are exhausted the exception propagates so the
   * cycle is marked FAILED and the schedule survives (never crashes).
   */
  private java.util.List<SyncSourceRow> readWithRetry(String lastSheetNo) {
    var watermark = lastSheetNo == null ? "" : lastSheetNo;
    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_QUERY_RETRIES; attempt++) {
      try {
        return sourceReader.readBatch(watermark, properties.batchSize());
      } catch (RuntimeException e) {
        lastFailure = e;
        if (attempt < MAX_QUERY_RETRIES) {
          log.warn("[SyncWorker] external DB read failed attempt={}/{} — backing off 5s: {}",
              attempt, MAX_QUERY_RETRIES, e.getMessage());
          backoff.accept(RETRY_BACKOFF);
        }
      }
    }
    throw lastFailure;
  }

  private static void sleepBackoff(Duration backoff) {
    try {
      Thread.sleep(backoff.toMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("sync worker interrupted during backoff", interrupted);
    }
  }

  private String lastRowOf(java.util.List<SyncSourceRow> batch) {
    return batch.get(batch.size() - 1).sheetNo();
  }

  private void releaseLock(String instanceId) {
    try {
      var current = redis.opsForValue().get(LOCK_KEY);
      if (instanceId.equals(current)) {
        redis.delete(LOCK_KEY);
      }
    } catch (RuntimeException e) {
      // Lock auto-expires via TTL; a failed release must not break the cycle.
      log.warn("[SyncWorker] lock release failed — TTL will expire it: {}", e.getMessage());
    }
  }

  private String safeMessage(Exception e) {
    var message = e.getMessage();
    return message == null ? e.getClass().getSimpleName() : message;
  }
}