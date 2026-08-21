package com.syncro.notification.application;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * In-memory notification worker observability: tracks the last poll execution time so the
 * notification worker health status can report whether the dispatch loop is alive.
 *
 * <p>Values are intentionally in-memory and reset on restart — a restart restarts the poll
 * clock (documented in the story dev notes). Never a source of truth; observability only.
 */
@Component
public class NotificationWorkerTracker {

  private final Clock clock;
  private volatile Instant lastPollAt;

  public NotificationWorkerTracker(Clock clock) {
    this.clock = clock;
  }

  /** Records a poll cycle; {@code lastPollAt} becomes {@code now}. Monotonic — never regresses. */
  public synchronized void recordPoll() {
    Instant now = Instant.now(clock);
    if (lastPollAt == null || now.isAfter(lastPollAt)) {
      lastPollAt = now;
    }
  }

  /** Timestamp of the last poll cycle, or {@code null} before the first poll. */
  public Instant lastPollAt() {
    return lastPollAt;
  }
}
