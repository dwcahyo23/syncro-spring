package com.syncro.telemetry.application;

@FunctionalInterface
public interface TelemetryValidator {
  TelemetryValidationService.Result validate(String topic, String payload);
}
