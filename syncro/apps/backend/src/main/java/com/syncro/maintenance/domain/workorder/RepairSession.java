package com.syncro.maintenance.domain.workorder;

import java.time.Instant;
import java.util.UUID;

/**
 * Repair session value (story 10-4). A non-overlapping interval a technician (or
 * in-scope leader) logs against an IN_PROGRESS workorder; {@code endedAt} is null
 * while the session is open and {@code durationMinutes} is populated when it closes
 * ({@code Duration.between(startedAt, endedAt).toMinutes()}, server clock only).
 * Sessions are local operational fields (AD-3 "preserved") — they never touch the
 * workorder status or sync_version.
 */
public record RepairSession(
    UUID id,
    String workOrderId,
    UUID technicianId,
    String description,
    Instant startedAt,
    Instant endedAt,
    Long durationMinutes) {
}
