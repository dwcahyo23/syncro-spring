package com.syncro.telemetry.application;

/**
 * Overall data-quality state of the telemetry ingest path (windowed metrics basis).
 *
 * <p>Each value carries the operational status contract label and severity (architecture
 * severity set: SUCCESS/WARNING/CRITICAL). Severity strings are kept as local constants (same
 * taxonomy as {@code TelemetryFreshnessState}) so the telemetry module does not depend on the
 * health module (AR-014 module boundaries).
 */
public enum TelemetryDataQualityState {

  GOOD("Good", "SUCCESS"),
  DEGRADED("Degraded", "WARNING"),
  CRITICAL("Critical", "CRITICAL");

  private final String statusLabel;
  private final String statusSeverity;

  TelemetryDataQualityState(String statusLabel, String statusSeverity) {
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
