package com.syncro.telemetry.application;

import com.syncro.config.TelemetryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Derives the telemetry ingest path freshness state from the in-memory ingest tracker.
 *
 * <p>Freshness is backend-owned: {@code lastAcceptedAt} from {@link TelemetryIngestTracker} is
 * compared against {@code syncro.telemetry.ingest.stale-threshold}. Stale is strictly greater
 * than the threshold ÔÇö exactly at the threshold the telemetry is still considered live.
 * {@code lastAcceptedAt == null} (no telemetry ever accepted since process start) is NO_DATA,
 * never a healthy false-positive.
 *
 * <p>Derivation is equivalent to {@link IngestWorkerStatusService}: the same
 * {@code elapsed > staleThreshold} stale rule and {@code staleSince = lastAcceptedAt + threshold},
 * with an explicit negative-elapsed clock-skew branch (LIVE) so a backward clock step never
 * reports a misleading negative age or STALE.
 */
@Service
public class TelemetryFreshnessService {

  /** Empty-state reason shown when no telemetry has ever been accepted (page-spec 4.10). */
  public static final String NO_DATA_REASON =
      "No telemetry received. Verify MQTT configuration and machine setup.";

  private final TelemetryIngestTracker tracker;
  private final TelemetryProperties properties;
  private final Clock clock;

  public TelemetryFreshnessService(TelemetryIngestTracker tracker,
      TelemetryProperties properties, Clock clock) {
    this.tracker = tracker;
    this.properties = properties;
    this.clock = clock;
  }

  public TelemetryFreshnessStatus freshness() {
    Instant lastAcceptedAt = tracker.lastAcceptedAt();
    Duration staleThreshold = properties.ingest().staleThreshold();
    Instant now = clock.instant();

    if (lastAcceptedAt == null) {
      return new TelemetryFreshnessStatus(
          TelemetryFreshnessState.NO_DATA,
          TelemetryFreshnessState.NO_DATA.statusLabel(),
          TelemetryFreshnessState.NO_DATA.statusSeverity(),
          NO_DATA_REASON,
          now.toString(),
          null,
          null);
    }

    Duration elapsed = Duration.between(lastAcceptedAt, now);
    if (elapsed.isNegative()) {
      // Clock skew: lastAcceptedAt in the future relative to now. Report LIVE (not stale)
      // and avoid a misleading negative elapsed; the absolute timestamp still shows the value.
      return new TelemetryFreshnessStatus(
          TelemetryFreshnessState.LIVE,
          TelemetryFreshnessState.LIVE.statusLabel(),
          TelemetryFreshnessState.LIVE.statusSeverity(),
          null,
          now.toString(),
          lastAcceptedAt.toString(),
          null);
    }
    if (elapsed.compareTo(staleThreshold) > 0) {
      return new TelemetryFreshnessStatus(
          TelemetryFreshnessState.STALE,
          TelemetryFreshnessState.STALE.statusLabel(),
          TelemetryFreshnessState.STALE.statusSeverity(),
          "No telemetry accepted since " + lastAcceptedAt,
          now.toString(),
          lastAcceptedAt.toString(),
          lastAcceptedAt.plus(staleThreshold).toString());
    }

    return new TelemetryFreshnessStatus(
        TelemetryFreshnessState.LIVE,
        TelemetryFreshnessState.LIVE.statusLabel(),
        TelemetryFreshnessState.LIVE.statusSeverity(),
        null,
        now.toString(),
        lastAcceptedAt.toString(),
        null);
  }
}
