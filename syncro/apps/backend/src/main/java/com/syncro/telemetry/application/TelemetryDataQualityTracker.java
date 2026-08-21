package com.syncro.telemetry.application;

import com.syncro.config.TelemetryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLongArray;
import org.springframework.stereotype.Component;

/**
 * In-memory telemetry data-quality observability: windowed counters for accepted, quarantined,
 * anomaly and dead-lettered messages plus the last publish-to-visible latency sample.
 *
 * <p>Values are intentionally in-memory and reset on restart — same philosophy as
 * {@link TelemetryIngestTracker}: observability only, never a source of truth. The durable
 * quarantine table remains the drill-down history for rejected payloads.
 *
 * <p>Counts live in bounded minute-bucket ring counters (one bucket per minute, ring sized from
 * the configured window plus boundary slack), so memory stays constant regardless of message
 * volume — no per-event buffers. Counts are exact at minute granularity.
 *
 * <p>Definitions: an <em>anomaly</em> is a quarantined message whose rejection reason is
 * {@code out_of_range} (implausible field value — page-spec 4.4 "values outside plausible
 * range"). A <em>dead-letter</em> is a terminal ingest failure: a message that passed
 * validation but whose processing threw (the ingest path has no retry by design; the handler
 * catch is terminal). The <em>latency</em> sample is measured from the payload publish
 * timestamp to the moment the Redis latest write succeeds (publish → dashboard-visible);
 * negative samples (device clock ahead of the server) clamp to 0.
 *
 * <p>Known blind spots, deliberate: a message whose enrichment or validation itself throws
 * (before any handler branch) is not counted in any counter — it is only error-logged. A
 * quarantined message is counted at rejection time, before the durable quarantine write, so
 * a failing quarantine store can transiently diverge the panel from the quarantine log;
 * the counters observe pipeline stages, not durable outcomes, matching how accepted messages
 * are counted before persistence.
 */
@Component
public class TelemetryDataQualityTracker {

  /**
   * Quarantine rejection reason for implausible field values. Aliases the payload contract
   * constant so the anomaly classification cannot drift from what validation actually emits.
   */
  static final String ANOMALY_REJECTION_REASON = TelemetryPayload.REASON_OUT_OF_RANGE;

  /** Sentinel for "no latency sample yet"; never exposed as a positive measurement. */
  static final long NO_LATENCY_SAMPLE = -1;

  private final Clock clock;
  private final long windowMinutes;
  private final int bucketCount;
  private final AtomicLongArray acceptedBuckets;
  private final AtomicLongArray acceptedMinutes;
  private final AtomicLongArray quarantinedBuckets;
  private final AtomicLongArray quarantinedMinutes;
  private final AtomicLongArray anomalyBuckets;
  private final AtomicLongArray anomalyMinutes;
  private final AtomicLongArray deadLetterBuckets;
  private final AtomicLongArray deadLetterMinutes;
  private volatile LatencySample lastLatencySample;

  public TelemetryDataQualityTracker(Clock clock, TelemetryProperties properties) {
    this.clock = clock;
    // Minute granularity: sub-minute windows count the current minute, windows round up.
    this.windowMinutes = Math.max(1, properties.dataQuality().window().toMinutes());
    this.bucketCount = (int) (windowMinutes + 2);
    this.acceptedBuckets = new AtomicLongArray(bucketCount);
    this.acceptedMinutes = new AtomicLongArray(bucketCount);
    this.quarantinedBuckets = new AtomicLongArray(bucketCount);
    this.quarantinedMinutes = new AtomicLongArray(bucketCount);
    this.anomalyBuckets = new AtomicLongArray(bucketCount);
    this.anomalyMinutes = new AtomicLongArray(bucketCount);
    this.deadLetterBuckets = new AtomicLongArray(bucketCount);
    this.deadLetterMinutes = new AtomicLongArray(bucketCount);
  }

  /** Records a message that passed validation (dedupe-suppressed duplicates included). */
  public void recordAccepted() {
    increment(acceptedBuckets, acceptedMinutes);
  }

  /**
   * Records a validation rejection. When {@code reason} is {@link #ANOMALY_REJECTION_REASON}
   * the anomaly counter increments alongside the quarantine counter.
   */
  public void recordQuarantined(String reason) {
    increment(quarantinedBuckets, quarantinedMinutes);
    if (ANOMALY_REJECTION_REASON.equals(reason)) {
      increment(anomalyBuckets, anomalyMinutes);
    }
  }

  /** Records a terminal ingest failure (message passed validation, processing threw). */
  public void recordDeadLettered() {
    increment(deadLetterBuckets, deadLetterMinutes);
  }

  /**
   * Records the last publish-to-visible latency; negative values clamp to 0 (clock skew).
   * The sample carries its minute so it can age out with the window (see {@link #snapshot()}).
   */
  public void recordLatencyMs(long latencyMs) {
    this.lastLatencySample = new LatencySample(currentMinute(), Math.max(0, latencyMs));
  }

  /**
   * Windowed counts as of now. {@code lastLatencyMs} is {@link #NO_LATENCY_SAMPLE} before the
   * first sample AND once the newest sample has left the window — a stalled pipeline must
   * surface "no data", not a stale latency frozen at its last value. The minute and the
   * value travel in one immutable sample so a concurrent reader never pairs them across
   * updates.
   */
  public Snapshot snapshot() {
    long currentMinute = currentMinute();
    long fromMinute = currentMinute - windowMinutes + 1;
    LatencySample sample = lastLatencySample;
    long lastLatencyMs = sample == null || sample.minute() < fromMinute
        ? NO_LATENCY_SAMPLE
        : sample.latencyMs();
    return new Snapshot(
        count(acceptedBuckets, acceptedMinutes, fromMinute, currentMinute),
        count(quarantinedBuckets, quarantinedMinutes, fromMinute, currentMinute),
        count(anomalyBuckets, anomalyMinutes, fromMinute, currentMinute),
        count(deadLetterBuckets, deadLetterMinutes, fromMinute, currentMinute),
        lastLatencyMs);
  }

  /** Effective window in seconds at the tracker's minute granularity; never below one minute. */
  public long effectiveWindowSeconds() {
    return windowMinutes * 60;
  }

  private long currentMinute() {
    return clock.instant().getEpochSecond() / 60;
  }

  /**
   * Claims the bucket for the current minute (resetting stale counts from an earlier ring
   * cycle) and increments. Synchronized so a claim/reset can never race an increment and lose
   * it; the handler threads calling this are few and the work is two array cells. The bucket
   * is zeroed BEFORE the minute tag is published so a lock-free reader can never pair a fresh
   * tag with the previous cycle's count (worst case it briefly skips a just-reclaimed bucket).
   */
  private synchronized void increment(AtomicLongArray buckets, AtomicLongArray minutes) {
    long minute = currentMinute();
    int index = (int) Math.floorMod(minute, bucketCount);
    if (minutes.get(index) != minute) {
      buckets.set(index, 0);
      minutes.set(index, minute);
    }
    buckets.incrementAndGet(index);
  }

  /** Sums bucket counts whose recorded minute falls inside the window; lock-free. */
  private static long count(AtomicLongArray buckets, AtomicLongArray minutes, long fromMinute,
      long currentMinute) {
    long total = 0;
    for (long minute = fromMinute; minute <= currentMinute; minute++) {
      int index = (int) Math.floorMod(minute, buckets.length());
      if (minutes.get(index) == minute) {
        total += buckets.get(index);
      }
    }
    return total;
  }

  /** Windowed data-quality counts plus the last latency sample. */
  public record Snapshot(
      long acceptedCount,
      long quarantinedCount,
      long anomalyCount,
      long deadLetterCount,
      long lastLatencyMs) {
  }

  /** Minute-tagged latency sample so the value ages out with the window, atomically. */
  private record LatencySample(long minute, long latencyMs) {
  }
}
