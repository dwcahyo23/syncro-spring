package com.syncro.telemetry.application;

import java.time.Instant;
import java.util.UUID;

/**
 * One ACTIVE machine whose latest telemetry is not fresh (per-machine evidence item for the
 * SUPER_ADMIN health dashboard, Story 6.6).
 *
 * @param machineId       machine identifier (machine hub deep-link target resolves by code)
 * @param machineCode     stable machine code, e.g. {@code BF-08410}
 * @param plantCode       plant code the machine belongs to
 * @param freshnessState  per-machine freshness enum name: {@code OFFLINE} (5–15 min or never
 *                        received) or {@code STALE} (&gt;15 min); never {@code ONLINE} here
 * @param statusLabel     display label from {@link LatestTelemetryDto.FreshnessState#label()}
 * @param lastReceivedAt  instant of the machine's last accepted telemetry, or {@code null} when
 *                        the machine never sent telemetry (or its latest state could not be read)
 */
public record StaleMachineItem(
    UUID machineId,
    String machineCode,
    String plantCode,
    String freshnessState,
    String statusLabel,
    Instant lastReceivedAt) {
}
