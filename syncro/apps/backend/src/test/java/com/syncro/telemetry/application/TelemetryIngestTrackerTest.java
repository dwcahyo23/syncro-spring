package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TelemetryIngestTrackerTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-08-21T08:00:00Z");
  private static final Clock FIXED_CLOCK =
      Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

  private final TelemetryIngestTracker tracker = new TelemetryIngestTracker(FIXED_CLOCK);

  @Test
  void initialStateHasNoAcceptedTelemetry() {
    assertThat(tracker.lastAcceptedAt()).isNull();
    assertThat(tracker.acceptedCount()).isZero();
  }

  @Test
  void recordAcceptedSetsLastAcceptedAtToNowAndIncrementsCount() {
    tracker.recordAccepted();

    assertThat(tracker.lastAcceptedAt()).isEqualTo(FIXED_NOW);
    assertThat(tracker.acceptedCount()).isEqualTo(1);
  }

  @Test
  void recordAcceptedKeepsLatestTimestamp() {
    tracker.recordAccepted();

    Clock laterClock = Clock.fixed(FIXED_NOW.plusSeconds(30), ZoneOffset.UTC);
    TelemetryIngestTracker advancing = new TelemetryIngestTracker(laterClock);
    advancing.recordAccepted();
    advancing.recordAccepted();

    assertThat(advancing.lastAcceptedAt()).isEqualTo(FIXED_NOW.plusSeconds(30));
    assertThat(advancing.acceptedCount()).isEqualTo(2);
  }

  @Test
  void backwardClockStepMovesLastAcceptedAtBackward() {
    // DW-69: a backward NTP step must not pin lastAcceptedAt in the future. A fresh tracker
    // whose clock is fixed behind a previously-observed time must report the lower time, so
    // the freshness service's clock-skew branch can distinguish a genuinely stalled ingest.
    var backward = new TelemetryIngestTracker(Clock.fixed(FIXED_NOW.minusSeconds(60), ZoneOffset.UTC));
    backward.recordAccepted();

    assertThat(backward.lastAcceptedAt()).isEqualTo(FIXED_NOW.minusSeconds(60));
    assertThat(backward.acceptedCount()).isEqualTo(1);
  }
}
