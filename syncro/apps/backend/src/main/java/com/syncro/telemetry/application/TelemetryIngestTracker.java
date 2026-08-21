package com.syncro.telemetry.application;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * In-memory ingest worker telemetry: tracks the last accepted telemetry timestamp and the
 * lifetime accepted-message counter so the ingest worker health status can report freshness
 * and staleness.
 *
 * <p>Values are intentionally in-memory and reset on restart — a restart restarts the accept
 * clock (documented in the story dev notes). Never a source of truth; observability only.
 */
@Component
public class TelemetryIngestTracker {

  private final Clock clock;
  private final AtomicLong acceptedCount = new AtomicLong();
  private volatile Instant lastAcceptedAt;

  public TelemetryIngestTracker(Clock clock) {
    this.clock = clock;
  }

  /** Records an accepted telemetry message; {@code lastAcceptedAt} becomes {@code now}. */
  public synchronized void recordAccepted() {
    Instant now = Instant.now(clock);
    if (lastAcceptedAt == null || now.isAfter(lastAcceptedAt)) {
      lastAcceptedAt = now;
    }
    acceptedCount.incrementAndGet();
  }

  /** Timestamp of the last accepted telemetry message, or {@code null} before the first. */
  public Instant lastAcceptedAt() {
    return lastAcceptedAt;
  }

  /** Lifetime count of accepted telemetry messages since this process started. */
  public long acceptedCount() {
    return acceptedCount.get();
  }
}
