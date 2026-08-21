package com.syncro.telemetry.application;

/**
 * Freshness state of the telemetry ingest path (global, backend-last-accepted basis).
 *
 * <p>Distinct from {@code com.syncro.telemetry.application.TelemetryFreshnessCalculator},
 * which derives a per-machine {@code ONLINE}/{@code OFFLINE}/{@code STALE} freshness from
 * per-machine latest-telemetry records with different (hardcoded) thresholds. This enum is the
 * global ingest-path freshness contract used by the health dashboard ({@code NO_DATA} /
 * {@code LIVE} / {@code STALE}, threshold from {@code syncro.telemetry.ingest.stale-threshold}).
 *
 * <p>Each value carries the operational status contract label and severity
 * (architecture severity set: INFO/SUCCESS/WARNING/CRITICAL/NEUTRAL). Severity strings are
 * kept as local constants (same taxonomy as {@code DependencyHealthSupport}) so the telemetry
 * module does not depend on the health module (AR-014 module boundaries).
 */
public enum TelemetryFreshnessState {

  NO_DATA("No data", "NEUTRAL"),
  LIVE("Live", "SUCCESS"),
  STALE("Stale", "WARNING");

  private final String statusLabel;
  private final String statusSeverity;

  TelemetryFreshnessState(String statusLabel, String statusSeverity) {
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
