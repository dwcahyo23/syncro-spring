package com.syncro.notification.application;

import java.util.UUID;

/**
 * Notification dispatch worker health status (SUPER_ADMIN-only endpoint payload).
 *
 * <p>Follows the operational status contract field set ({@code status}, {@code statusLabel},
 * {@code statusReason}, {@code statusSeverity}, {@code timestamp}) extended with worker
 * observability fields. {@code status} serializes to the enum name ({@code RUNNING} /
 * {@code STOPPED} / {@code DEGRADED}).
 *
 * @param status              notification worker state
 * @param statusLabel         human label, e.g. {@code Running}
 * @param statusSeverity      canonical severity, e.g. {@code SUCCESS}
 * @param statusReason        descriptive reason (this endpoint is SUPER_ADMIN-protected, so
 *                            failure text is acceptable)
 * @param timestamp           ISO instant the status was computed
 * @param lastPollAt          ISO instant of the last worker poll cycle, or {@code null}
 * @param staleSince          ISO instant the worker crossed the stale threshold, or {@code null}
 * @param pendingJobCount     jobs still waiting for dispatch (PENDING + RATE_LIMITED)
 * @param recentFailedCount   FAILED WAHA attempts within the configured failed window
 * @param lastFailureReason   response detail of the most recent FAILED attempt, or {@code null}
 * @param lastFailedAlertId   id of the alert whose notification failed most recently within the
 *                            failed window (evidence deep-link target), or {@code null} when no
 *                            FAILED attempt exists in the window or its job no longer exists
 * @param lastSuccessfulSendAt ISO instant of the latest successful WAHA send, or {@code null}
 * @param circuitBreakerState WAHA circuit breaker state name, e.g. {@code CLOSED} / {@code OPEN}
 */
public record NotificationWorkerStatus(
    NotificationWorkerState status,
    String statusLabel,
    String statusSeverity,
    String statusReason,
    String timestamp,
    String lastPollAt,
    String staleSince,
    long pendingJobCount,
    long recentFailedCount,
    String lastFailureReason,
    UUID lastFailedAlertId,
    String lastSuccessfulSendAt,
    String circuitBreakerState) {
}
