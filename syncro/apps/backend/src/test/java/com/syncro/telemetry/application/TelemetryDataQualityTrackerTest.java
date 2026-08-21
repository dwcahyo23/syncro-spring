package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.config.TelemetryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class TelemetryDataQualityTrackerTest {

  private static final Instant START = Instant.parse("2026-08-22T10:00:00Z");

  private final MutableClock clock = new MutableClock(START);

  private TelemetryDataQualityTracker tracker(Duration window) {
    return new TelemetryDataQualityTracker(clock, properties(window));
  }

  private static TelemetryProperties properties(Duration window) {
    return new TelemetryProperties(Duration.parse("PT5M"), Duration.parse("PT30S"),
        new TelemetryProperties.Ingest(1000, 2, Duration.ofMinutes(5)),
        new TelemetryProperties.DataQuality(window));
  }

  @Test
  void countersAccumulateWithinWindow() {
    var quality = tracker(Duration.ofHours(1));

    quality.recordAccepted();
    quality.recordAccepted();
    quality.recordAccepted();
    quality.recordQuarantined("unknown_machine");
    quality.recordQuarantined("out_of_range");
    quality.recordDeadLettered();
    quality.recordLatencyMs(1200);

    var snapshot = quality.snapshot();
    assertThat(snapshot.acceptedCount()).isEqualTo(3);
    assertThat(snapshot.quarantinedCount()).isEqualTo(2);
    assertThat(snapshot.anomalyCount()).isEqualTo(1);
    assertThat(snapshot.deadLetterCount()).isEqualTo(1);
    assertThat(snapshot.lastLatencyMs()).isEqualTo(1200);
  }

  @Test
  void anomalyCountOnlyTracksOutOfRangeRejections() {
    var quality = tracker(Duration.ofHours(1));

    quality.recordQuarantined("unknown_machine");
    quality.recordQuarantined("invalid_timestamp");
    quality.recordQuarantined("out_of_range");

    var snapshot = quality.snapshot();
    assertThat(snapshot.quarantinedCount()).isEqualTo(3);
    assertThat(snapshot.anomalyCount()).isEqualTo(1);
  }

  @Test
  void eventsOlderThanWindowAreExcluded() {
    var quality = tracker(Duration.ofHours(1));

    quality.recordAccepted();
    quality.recordQuarantined("out_of_range");
    quality.recordDeadLettered();
    clock.advance(Duration.ofMinutes(61));

    var snapshot = quality.snapshot();
    assertThat(snapshot.acceptedCount()).isZero();
    assertThat(snapshot.quarantinedCount()).isZero();
    assertThat(snapshot.anomalyCount()).isZero();
    assertThat(snapshot.deadLetterCount()).isZero();
  }

  @Test
  void windowEdgeIsInclusiveForEventsInsideTheWindow() {
    var quality = tracker(Duration.ofHours(1));

    // 10:01 event stays inside a 1h window snapshotted at 11:00 (from-minute 10:01 inclusive);
    // a 10:00 event falls one minute outside it.
    clock.advance(Duration.ofMinutes(1));
    quality.recordAccepted();
    clock.advance(Duration.ofMinutes(59));
    assertThat(quality.snapshot().acceptedCount()).isEqualTo(1);

    var older = tracker(Duration.ofHours(1));
    older.recordAccepted();
    clock.advance(Duration.ofMinutes(60));
    assertThat(older.snapshot().acceptedCount()).isZero();
  }

  @Test
  void ringWrapReclaimsStaleBucketsWithoutBleedingOldCounts() {
    // Small window keeps the ring short so a wrap definitely happens mid-test.
    var quality = tracker(Duration.ofMinutes(2));

    quality.recordAccepted();
    quality.recordAccepted();
    clock.advance(Duration.ofMinutes(5));
    quality.recordAccepted();

    var snapshot = quality.snapshot();
    assertThat(snapshot.acceptedCount()).isEqualTo(1);
  }

  @Test
  void latencyStartsUnsampledAndClampsNegativeValuesToZero() {
    var quality = tracker(Duration.ofHours(1));

    assertThat(quality.snapshot().lastLatencyMs())
        .isEqualTo(TelemetryDataQualityTracker.NO_LATENCY_SAMPLE);

    quality.recordLatencyMs(-300);
    assertThat(quality.snapshot().lastLatencyMs()).isZero();

    quality.recordLatencyMs(4500);
    assertThat(quality.snapshot().lastLatencyMs()).isEqualTo(4500);
  }

  @Test
  void subMinuteWindowCountsCurrentMinute() {
    var quality = tracker(Duration.ofSeconds(30));

    quality.recordAccepted();
    quality.recordQuarantined("out_of_range");

    var snapshot = quality.snapshot();
    assertThat(snapshot.acceptedCount()).isEqualTo(1);
    assertThat(snapshot.anomalyCount()).isEqualTo(1);
  }

  @Test
  void latencySampleExpiresWithTheWindow() {
    var quality = tracker(Duration.ofMinutes(5));

    quality.recordLatencyMs(900);
    clock.advance(Duration.ofMinutes(3));
    assertThat(quality.snapshot().lastLatencyMs()).isEqualTo(900);

    // Six minutes after the sample the window has moved past it: a stalled pipeline must
    // report "no data" instead of freezing at the last observed latency.
    clock.advance(Duration.ofMinutes(3));
    assertThat(quality.snapshot().lastLatencyMs())
        .isEqualTo(TelemetryDataQualityTracker.NO_LATENCY_SAMPLE);

    quality.recordLatencyMs(1200);
    assertThat(quality.snapshot().lastLatencyMs()).isEqualTo(1200);
  }

  @Test
  void concurrentIncrementsAcrossMinuteBoundariesAreNeverLost() throws Exception {
    // Exercises the synchronized claim/reset protocol: two hammer rounds on either side of a
    // minute boundary must keep exact totals (a lost claim race would drop increments; a
    // wrap reset racing an increment would erase the earlier round).
    var quality = tracker(Duration.ofHours(1));
    int threads = 4;
    int perThread = 2_500;

    runConcurrentRecords(quality, threads, perThread);
    clock.advance(Duration.ofMinutes(1));
    runConcurrentRecords(quality, threads, perThread);

    var snapshot = quality.snapshot();
    long total = 2L * threads * perThread;
    assertThat(snapshot.acceptedCount()).isEqualTo(total);
    assertThat(snapshot.quarantinedCount()).isEqualTo(total);
    assertThat(snapshot.anomalyCount()).isEqualTo(total);
    assertThat(snapshot.deadLetterCount()).isEqualTo(total);
  }

  private static void runConcurrentRecords(TelemetryDataQualityTracker quality, int threads,
      int perThread) throws Exception {
    var executor = Executors.newFixedThreadPool(threads);
    try {
      var futures = new ArrayList<Future<?>>();
      for (int t = 0; t < threads; t++) {
        futures.add(executor.submit(() -> {
          for (int i = 0; i < perThread; i++) {
            quality.recordAccepted();
            quality.recordQuarantined("out_of_range");
            quality.recordDeadLettered();
          }
        }));
      }
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  /** Test-local mutable clock so bucket eviction can be exercised deterministically. */
  private static final class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void advance(Duration duration) {
      this.now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
