package com.syncro.telemetry.application;

/**
 * End-to-end telemetry latency state: publish (payload timestamp) to dashboard-visible
 * (Redis latest write). Product targets per SM-008 / epic-6 context: normal &lt; 5s,
 * elevated 5–15s, critical &gt; 15s. {@code NO_DATA} applies before the first sample
 * (including right after a restart).
 */
public enum LatencyState {

  NO_DATA("No data", "NEUTRAL"),
  NORMAL("Normal", "SUCCESS"),
  ELEVATED("Elevated", "WARNING"),
  CRITICAL("Critical", "CRITICAL");

  private final String statusLabel;
  private final String statusSeverity;

  LatencyState(String statusLabel, String statusSeverity) {
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
