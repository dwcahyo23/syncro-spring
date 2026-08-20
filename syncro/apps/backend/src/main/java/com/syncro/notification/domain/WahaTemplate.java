package com.syncro.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record WahaTemplate(
    UUID id,
    String templateKey,
    String body,
    Instant createdAt,
    Instant updatedAt) {

  public static final String DEFAULT_KEY = "alert_notification";

  public static final java.util.Set<String> KNOWN_VARIABLES = java.util.Set.of(
      "{machineCode}",
      "{machineName}",
      "{plantCode}",
      "{machineGroup}",
      "{sparepartName}",
      "{thresholdPercent}",
      "{currentCount}",
      "{alertTime}");
}
