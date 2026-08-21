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
 */
@Component
public class TelemetryDataQualityTracker {

  /** Quarantine rejection reason for implausible field values (TelemetryPayload range checks). */
  static final String ANOMALY_REJECTION_REASON = "out_of_range";

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
  private volatile long lastLatencyMs = NO_LATENCY_SAMPLE;

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

  /** Records the last publish-to-visible latency; negative values clamp to 0 (clock skew). */
  public void recordLatencyMs(long latencyMs) {
    this.lastLatencyMs = Math.max(0, latencyMs);
  }

  /** Windowed counts as of now; {@code lastLatencyMs} is {@link #NO_LATENCY_SAMPLE} before the first sample. */
  public Snapshot snapshot() {
    long currentMinute = currentMinute();
    long fromMinute = currentMinute - windowMinutes + 1;
    return new Snapshot(
        count(acceptedBuckets, acceptedMinutes, fromMinute, currentMinute),
        count(quarantinedBuckets, quarantinedMinutes, fromMinute, currentMinute),
        count(anomalyBuckets, anomalyMinutes, fromMinute, currentMinute),
        count(deadLetterBuckets, deadLetterMinutes, fromMinute, currentMinute),
        lastLatencyMs);
  }

  private long currentMinute() {
    return clock.instant().getEpochSecond() / 60;
  }

  /**
   * Claims the bucket for the current minute (resetting stale counts from an earlier ring
   * cycle) and increments. Synchronized so a claim/reset can never race an increment and lose
   * it; the handler threads calling this are few and the work is two array cells.
   */
  private synchronized void increment(AtomicLongArray buckets, AtomicLongArray minutes) {
    long minute = currentMinute();
    int index = (int) Math.floorMod(minute, bucketCount);
    if (minutes.get(index) != minute) {
      minutes.set(index, minute);
      buckets.set(index, 0);
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
}
