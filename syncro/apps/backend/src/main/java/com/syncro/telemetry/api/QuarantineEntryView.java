package com.syncro.telemetry.api;

import java.time.Instant;
import java.util.UUID;

public record QuarantineEntryView(
    UUID id,
    String traceId,
    String topic,
    String rawPayload,
    String rejectionReason,
    String rejectionField,
    Instant receivedAt
) {
}
