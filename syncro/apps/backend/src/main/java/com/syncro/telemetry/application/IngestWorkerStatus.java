package com.syncro.telemetry.application;

/**
 * Telemetry ingest worker health status (SUPER_ADMIN-only endpoint payload).
 *
 * <p>Follows the operational status contract field set ({@code status}, {@code statusLabel},
 * {@code statusReason}, {@code statusSeverity}, {@code timestamp}) extended with worker
 * observability fields. {@code status} serializes to the enum name ({@code RUNNING} /
 * {@code STOPPED} / {@code DEGRADED}).
 *
 * @param status        ingest worker state
 * @param statusLabel   human label, e.g. {@code Running}
 * @param statusSeverity canonical severity, e.g. {@code SUCCESS}
 * @param statusReason  descriptive reason (this endpoint is SUPER_ADMIN-protected, so broker
 *                      error text is acceptable)
 * @param timestamp     ISO instant the status was computed
 * @param mqttState     MQTT subscription state (UNKNOWN/SUBSCRIBED/FAILED), where available
 * @param lastAcceptedAt ISO instant of the last accepted telemetry, or {@code null}
 * @param staleSince    ISO instant the worker crossed the stale threshold, or {@code null}
 * @param queueDepth    current telemetry ingest queue depth
 * @param queueCapacity configured telemetry ingest queue capacity
 * @param acceptedCount lifetime accepted-message counter (in-memory, resets on restart)
 */
public record IngestWorkerStatus(
    IngestWorkerState status,
    String statusLabel,
    String statusSeverity,
    String statusReason,
    String timestamp,
    String mqttState,
    String lastAcceptedAt,
    String staleSince,
    int queueDepth,
    int queueCapacity,
    long acceptedCount) {
}
