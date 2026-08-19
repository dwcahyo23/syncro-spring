package com.syncro.telemetry.application;

import java.util.Optional;

public record TelemetryTopic(String plantCode, String machineCode) {

  public static Optional<TelemetryTopic> parse(String topic) {
    if (topic == null || topic.isEmpty() || topic.endsWith("/")) {
      return Optional.empty();
    }
    String[] segments = topic.split("/");
    if (segments.length != 4) {
      return Optional.empty();
    }
    if (!"factory".equals(segments[0]) || !"telemetry".equals(segments[3])) {
      return Optional.empty();
    }
    String plantCode = segments[1].trim();
    String machineCode = segments[2].trim();
    if (plantCode.isBlank() || machineCode.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new TelemetryTopic(plantCode, machineCode));
  }
}