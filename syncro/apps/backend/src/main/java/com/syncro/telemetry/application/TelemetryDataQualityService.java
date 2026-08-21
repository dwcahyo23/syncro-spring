package com.syncro.telemetry.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Derives the telemetry data-quality status from the windowed {@link TelemetryDataQualityTracker}
 * snapshot. Metric thresholds are product targets, not deployment knobs, and are pinned here
 * with their sources:
 * <ul>
 *   <li>Quarantine count &gt; 10 warning, &gt; 50 critical — page-spec 4.4</li>
 *   <li>Rejection rate &gt; 1% warning, &gt; 5% critical — page-spec 4.4 (SM-009 keeps the
 *       well-formed-payload rejection rate below 1%)</li>
 *   <li>Anomaly count &gt; 0 warning — page-spec 4.4</li>
 *   <li>Dead-letter count &gt; 0 critical — page-spec 4.4</li>
 *   <li>Latency ≥ 5s elevated, &gt; 15s critical — SM-008 / epic-6 context (5–15s band elevated)</li>
 * </ul>
 */
@Service
public class TelemetryDataQualityService {

  static final long QUARANTINED_WARNING = 10;
  static final long QUARANTINED_CRITICAL = 50;
  static final double REJECTION_RATE_WARNING_PCT = 1.0;
  static final double REJECTION_RATE_CRITICAL_PCT = 5.0;
  static final long LATENCY_ELEVATED_MS = 5_000;
  static final long LATENCY_CRITICAL_MS = 15_000;

  private static final String SUCCESS = "SUCCESS";
  private static final String WARNING = "WARNING";
  private static final String CRITICAL = "CRITICAL";

  private final TelemetryDataQualityTracker tracker;
  private final Clock clock;

  public TelemetryDataQualityService(TelemetryDataQualityTracker tracker, Clock clock) {
    this.tracker = tracker;
    this.clock = clock;
  }

  public TelemetryDataQualityStatus status() {
    var snapshot = tracker.snapshot();
    long received = snapshot.acceptedCount() + snapshot.quarantinedCount();
    double rawRate = received == 0 ? 0.0 : snapshot.quarantinedCount() * 100.0 / received;
    double rate = Math.round(rawRate * 100.0) / 100.0;

    // Severity decisions use the unrounded rate so the >1%/>5% boundaries hold exactly;
    // the rounded value is display/output only.
    String quarantinedSeverity = countSeverity(snapshot.quarantinedCount());
    String rejectionRateSeverity = rateSeverity(rawRate);
    String anomalySeverity = snapshot.anomalyCount() > 0 ? WARNING : SUCCESS;
    String deadLetterSeverity = snapshot.deadLetterCount() > 0 ? CRITICAL : SUCCESS;

    LatencyState latencyState = latencyState(snapshot.lastLatencyMs());

    TelemetryDataQualityState state = worstOf(quarantinedSeverity, rejectionRateSeverity,
        anomalySeverity, deadLetterSeverity, latencyState.statusSeverity());
    String reason = state == TelemetryDataQualityState.GOOD
        ? null
        : reason(snapshot, rawRate, latencyState);

    return new TelemetryDataQualityStatus(
        state,
        state.statusLabel(),
        state.statusSeverity(),
        reason,
        clock.instant().toString(),
        // The tracker windows at minute granularity; report its effective window, not the
        // configured duration, so the panel label never overstates the evidence horizon.
        tracker.effectiveWindowSeconds(),
        snapshot.quarantinedCount(),
        rate,
        snapshot.anomalyCount(),
        snapshot.deadLetterCount(),
        received,
        quarantinedSeverity,
        rejectionRateSeverity,
        anomalySeverity,
        deadLetterSeverity,
        snapshot.lastLatencyMs() == TelemetryDataQualityTracker.NO_LATENCY_SAMPLE
            ? null
            : snapshot.lastLatencyMs(),
        latencyState,
        latencyState.statusSeverity());
  }

  private static String countSeverity(long quarantined) {
    if (quarantined > QUARANTINED_CRITICAL) {
      return CRITICAL;
    }
    if (quarantined > QUARANTINED_WARNING) {
      return WARNING;
    }
    return SUCCESS;
  }

  private static String rateSeverity(double ratePct) {
    if (ratePct > REJECTION_RATE_CRITICAL_PCT) {
      return CRITICAL;
    }
    if (ratePct > REJECTION_RATE_WARNING_PCT) {
      return WARNING;
    }
    return SUCCESS;
  }

  static LatencyState latencyState(long lastLatencyMs) {
    if (lastLatencyMs == TelemetryDataQualityTracker.NO_LATENCY_SAMPLE) {
      return LatencyState.NO_DATA;
    }
    if (lastLatencyMs > LATENCY_CRITICAL_MS) {
      return LatencyState.CRITICAL;
    }
    if (lastLatencyMs >= LATENCY_ELEVATED_MS) {
      return LatencyState.ELEVATED;
    }
    return LatencyState.NORMAL;
  }

  private static TelemetryDataQualityState worstOf(String... severities) {
    TelemetryDataQualityState state = TelemetryDataQualityState.GOOD;
    for (String severity : severities) {
      if (CRITICAL.equals(severity)) {
        return TelemetryDataQualityState.CRITICAL;
      }
      if (WARNING.equals(severity)) {
        state = TelemetryDataQualityState.DEGRADED;
      }
    }
    return state;
  }

  private static String reason(TelemetryDataQualityTracker.Snapshot snapshot, double rawRate,
      LatencyState latencyState) {
    // Severity compares the raw rate; the reason text formats it with enough precision that
    // "X above threshold" never reads as a contradiction at the 2-decimal display rounding.
    String rateText = String.format(Locale.ROOT, "%.4f", rawRate);
    List<String> triggered = new ArrayList<>();
    if (snapshot.quarantinedCount() > QUARANTINED_CRITICAL) {
      triggered.add("quarantine count " + snapshot.quarantinedCount() + " above " + QUARANTINED_CRITICAL);
    } else if (snapshot.quarantinedCount() > QUARANTINED_WARNING) {
      triggered.add("quarantine count " + snapshot.quarantinedCount() + " above " + QUARANTINED_WARNING);
    }
    if (rawRate > REJECTION_RATE_CRITICAL_PCT) {
      triggered.add("rejection rate " + rateText + "% above " + REJECTION_RATE_CRITICAL_PCT + "%");
    } else if (rawRate > REJECTION_RATE_WARNING_PCT) {
      triggered.add("rejection rate " + rateText + "% above " + REJECTION_RATE_WARNING_PCT + "%");
    }
    if (snapshot.anomalyCount() > 0) {
      triggered.add(snapshot.anomalyCount() + " anomalous field value(s)");
    }
    if (snapshot.deadLetterCount() > 0) {
      triggered.add(snapshot.deadLetterCount() + " dead-lettered message(s)");
    }
    if (latencyState == LatencyState.CRITICAL || latencyState == LatencyState.ELEVATED) {
      triggered.add("latency state " + latencyState.statusLabel().toLowerCase());
    }
    return String.join("; ", triggered);
  }
}
