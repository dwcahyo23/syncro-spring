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

  /** Story 14-4 (FR-180): workorder lifecycle event template key. */
  public static final String WORKORDER_LIFECYCLE_KEY = "workorder_lifecycle";

  /** Story 14-4 (FR-181): 4-hour acknowledgment template key. */
  public static final String WORKORDER_ACK_KEY = "workorder_ack";

  public static final java.util.Set<String> KNOWN_VARIABLES = java.util.Set.of(
      "{machineCode}",
      "{machineName}",
      "{plantCode}",
      "{machineGroup}",
      "{sparepartName}",
      "{thresholdPercent}",
      "{currentCount}",
      "{alertTime}",
      // Story 14-4 workorder lifecycle variables
      "{workOrderId}",
      "{status}",
      "{eventLabel}",
      "{transitionedAt}",
      // Story 14-4 workorder ack variables
      "{ackDeadline}",
      "{ackLink}");
}
