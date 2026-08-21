package com.syncro.telemetry.application;

/**
 * Operational state of the telemetry ingest worker.
 *
 * <p>Each value carries the operational status contract label and severity
 * (architecture severity set: INFO/SUCCESS/WARNING/CRITICAL/NEUTRAL). Severity strings are
 * kept as local constants (same taxonomy as {@code DependencyHealthSupport}) so the telemetry
 * module does not depend on the health module (AR-014 module boundaries).
 */
public enum IngestWorkerState {

  RUNNING("Running", "SUCCESS"),
  STOPPED("Stopped", "CRITICAL"),
  DEGRADED("Degraded", "WARNING");

  private final String statusLabel;
  private final String statusSeverity;

  IngestWorkerState(String statusLabel, String statusSeverity) {
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
