package com.syncro.telemetry.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class LatestTelemetryDto {
  private LatestTelemetryDto() {
  }

  public record TelemetryData(
      UUID machineId,
      boolean running,
      Double runtimeHours,
      Long counting,
      Instant lastReceivedAt,
      FreshnessState freshnessState,
      Map<String, String> optionalFields,
      boolean hasOptionalFields) {
  }

  public enum FreshnessState {
    ONLINE("online", "Data received within last 5 minutes"),
    OFFLINE("offline", "No recent telemetry data (5–15 min ago)"),
    STALE("stale", "No recent telemetry data (>15 min ago)");

    private final String label;
    private final String description;

    FreshnessState(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }
}
