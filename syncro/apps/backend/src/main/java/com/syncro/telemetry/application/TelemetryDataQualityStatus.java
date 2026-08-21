package com.syncro.telemetry.application;

/**
 * Telemetry data-quality status (SUPER_ADMIN-only endpoint payload).
 *
 * <p>Follows the operational status contract field set ({@code status}, {@code statusLabel},
 * {@code statusSeverity}, {@code statusReason}, {@code timestamp}) extended with the windowed
 * data-quality metrics and the end-to-end latency sample. {@code status} serializes to the
 * enum name ({@code GOOD} / {@code DEGRADED} / {@code CRITICAL}).
 *
 * <p>All counts and the latency sample are sourced from the in-memory
 * {@link TelemetryDataQualityTracker}, which resets on restart — after a restart counts
 * rebuild as messages flow and {@code lastLatencyMs} is {@code null} until the first
 * accepted message is persisted. Never a source of truth; observability only. The durable
 * quarantine table (quarantine log) remains the drill-down history.
 *
 * <p>Per-metric severity fields use the architecture severity strings (SUCCESS/WARNING/
 * CRITICAL) with thresholds from page-spec 4.4; the latency state follows SM-008
 * (normal &lt; 5s, elevated 5–15s, critical &gt; 15s).
 *
 * @param status                 overall data-quality state (worst contributing metric)
 * @param statusLabel            human label, e.g. {@code Good}
 * @param statusSeverity         canonical severity, e.g. {@code SUCCESS}
 * @param statusReason           triggered conditions when not {@code GOOD}, else {@code null}
 * @param timestamp              ISO instant the status was computed
 * @param windowSeconds          metrics window length in seconds (page-spec 4.4, default 1 hour)
 * @param quarantinedCount       validation rejections in the window
 * @param rejectionRatePct       quarantined / (accepted + quarantined) × 100; 0.0 when no messages
 * @param anomalyCount           quarantined messages with implausible values ({@code out_of_range})
 * @param deadLetterCount        terminal ingest failures in the window (validation passed, processing threw)
 * @param receivedCount          accepted + quarantined in the window (rate denominator)
 * @param quarantinedSeverity    per-metric severity (page-spec 4.4: &gt;10 warning, &gt;50 critical)
 * @param rejectionRateSeverity  per-metric severity (page-spec 4.4: &gt;1% warning, &gt;5% critical)
 * @param anomalySeverity        per-metric severity (page-spec 4.4: &gt;0 warning)
 * @param deadLetterSeverity     per-metric severity (page-spec 4.4: &gt;0 critical)
 * @param lastLatencyMs          publish-to-visible latency of the last persisted message, or {@code null}
 * @param latencyState           latency band (SM-008: &lt;5s normal, 5–15s elevated, &gt;15s critical)
 * @param latencySeverity        severity of {@link #latencyState} (NEUTRAL for NO_DATA)
 */
public record TelemetryDataQualityStatus(
    TelemetryDataQualityState status,
    String statusLabel,
    String statusSeverity,
    String statusReason,
    String timestamp,
    long windowSeconds,
    long quarantinedCount,
    double rejectionRatePct,
    long anomalyCount,
    long deadLetterCount,
    long receivedCount,
    String quarantinedSeverity,
    String rejectionRateSeverity,
    String anomalySeverity,
    String deadLetterSeverity,
    Long lastLatencyMs,
    LatencyState latencyState,
    String latencySeverity) {
}
