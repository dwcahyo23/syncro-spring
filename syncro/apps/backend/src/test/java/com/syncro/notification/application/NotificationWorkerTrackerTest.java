package com.syncro.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class NotificationWorkerTrackerTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-08-21T08:00:00Z");
  private static final Clock FIXED_CLOCK =
      Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

  private final NotificationWorkerTracker tracker = new NotificationWorkerTracker(FIXED_CLOCK);

  @Test
  void initialStateHasNoPollRecorded() {
    assertThat(tracker.lastPollAt()).isNull();
  }

  @Test
  void recordPollSetsLastPollAtToNow() {
    tracker.recordPoll();

    assertThat(tracker.lastPollAt()).isEqualTo(FIXED_NOW);
  }

  @Test
  void recordPollKeepsLatestTimestamp() {
    tracker.recordPoll();

    Clock laterClock = Clock.fixed(FIXED_NOW.plusSeconds(30), ZoneOffset.UTC);
    NotificationWorkerTracker advancing = new NotificationWorkerTracker(laterClock);
    advancing.recordPoll();
    advancing.recordPoll();

    assertThat(advancing.lastPollAt()).isEqualTo(FIXED_NOW.plusSeconds(30));
  }

  @Test
  void recordPollDoesNotRegressTimestampOnEarlierWrite() {
    MutableClock clock = new MutableClock(FIXED_NOW);
    NotificationWorkerTracker mutable = new NotificationWorkerTracker(clock);
    clock.set(FIXED_NOW.plusSeconds(30));
    mutable.recordPoll();
    assertThat(mutable.lastPollAt()).isEqualTo(FIXED_NOW.plusSeconds(30));

    // An earlier write (clock moved back, e.g. NTP adjustment) must not regress the timestamp.
    clock.set(FIXED_NOW.plusSeconds(10));
    mutable.recordPoll();

    assertThat(mutable.lastPollAt()).isEqualTo(FIXED_NOW.plusSeconds(30));
  }

  /** Minimal mutable clock for the monotonic-guard regression test. */
  private static final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant now) {
      this.now = now;
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      throw new UnsupportedOperationException();
    }
  }
}
