package com.syncro.telemetry.application;

import java.util.List;

/**
 * Per-machine stale-telemetry evidence (SUPER_ADMIN-only endpoint payload, Story 6.6).
 *
 * <p>Backs the health dashboard's "machines with stale telemetry" evidence list: ACTIVE machines
 * whose latest telemetry freshness is {@code OFFLINE} or {@code STALE} per
 * {@link TelemetryFreshnessCalculator} (&gt;5 minutes since last accepted telemetry, or no
 * telemetry at all). Items are ordered worst-first: never-received machines, then stalest
 * {@code lastReceivedAt}, then {@code machineCode} ascending.
 *
 * @param timestamp         ISO instant the status was computed
 * @param staleMachineCount number of stale items (== {@code items.size()})
 * @param items             stale machine evidence items, possibly empty
 */
public record StaleMachineStatus(
    String timestamp,
    int staleMachineCount,
    List<StaleMachineItem> items) {
}
