package com.syncro.telemetry.application;

import java.time.Instant;

public record TelemetryEnvelope(String traceId, String topic, String payload, Instant receivedAt) {
}
