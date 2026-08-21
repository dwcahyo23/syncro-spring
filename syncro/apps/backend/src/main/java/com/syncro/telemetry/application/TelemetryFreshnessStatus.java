package com.syncro.telemetry.application;

/**
 * Telemetry freshness status (SUPER_ADMIN-only endpoint payload).
 *
 * <p>Follows the operational status contract field set ({@code status}, {@code statusLabel},
 * {@code statusReason}, {@code statusSeverity}, {@code timestamp}) extended with the ingest
 * path freshness fields. {@code status} serializes to the enum name ({@code NO_DATA} /
 * {@code LIVE} / {@code STALE}).
 *
 * <p>{@code lastAcceptedAt} is sourced from the in-memory {@link TelemetryIngestTracker}, which
 * resets on restart — after a restart {@code lastAcceptedAt} is {@code null} until the first
 * telemetry message is accepted (NO_DATA), then the accept clock restarts. Never a source of
 * truth; observability only.
 *
 * @param status         freshness state
 * @param statusLabel    human label, e.g. {@code Live}
 * @param statusSeverity canonical severity, e.g. {@code SUCCESS}
 * @param statusReason   descriptive reason, or {@code null} when live
 * @param timestamp      ISO instant the status was computed
 * @param lastAcceptedAt ISO instant of the last accepted telemetry, or {@code null} (NO_DATA)
 * @param staleSince     ISO instant the freshness crossed the stale threshold, or {@code null}
 */
public record TelemetryFreshnessStatus(
    TelemetryFreshnessState status,
    String statusLabel,
    String statusSeverity,
    String statusReason,
    String timestamp,
    String lastAcceptedAt,
    String staleSince) {
}
