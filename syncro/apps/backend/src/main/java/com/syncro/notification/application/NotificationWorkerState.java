package com.syncro.notification.application;

/**
 * Operational state of the notification dispatch worker.
 *
 * <p>Each value carries the operational status contract label and severity
 * (architecture severity set: INFO/SUCCESS/WARNING/CRITICAL/NEUTRAL). Severity strings are
 * kept as local constants (same taxonomy as {@code DependencyHealthSupport}) so the notification
 * module does not depend on the health module (AR-014 module boundaries).
 */
public enum NotificationWorkerState {

  RUNNING("Running", "SUCCESS"),
  STOPPED("Stopped", "CRITICAL"),
  DEGRADED("Degraded", "WARNING");

  private final String statusLabel;
  private final String statusSeverity;

  NotificationWorkerState(String statusLabel, String statusSeverity) {
    this.statusLabel = statusLabel;
    this.statusSeverity = statusSeverity;
  }

  public String statusLabel() {
    return statusLabel;
  }

  public String statusSeverity() {
    return statusSeverity;
  }
}
