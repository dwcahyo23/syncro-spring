package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.config.TelemetryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

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
