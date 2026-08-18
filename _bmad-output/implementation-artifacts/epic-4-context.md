# Epic 4 Context: Sparepart Lifetime Alerting

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Users can detect sparepart production-count threshold risk, understand why an alert fired, acknowledge or resolve alerts, and preserve alert history.

## Stories

- Story 4.1: Evaluate Sparepart Lifetime Threshold from Accepted Telemetry
- Story 4.2: Create Threshold Alert with Duplicate Prevention
- Story 4.3: Show Alert List and Alert Detail
- Story 4.4: Acknowledge Open Alert
- Story 4.5: Resolve Acknowledged Alert
- Story 4.6: Allow SUPER_ADMIN Direct Resolve Override
- Story 4.7: Add Alert State to Machine Hub and Operations Overview

## Requirements & Constraints

**Threshold calculation.** Consumed production count is derived from the current counter (from accepted telemetry Redis latest state) relative to the `baselineCounter` recorded at installation. The formula uses 16-bit wrap-safe delta (same `CountingDeltaCalculator` already used by Epic 3). `consumedPercentage = consumedProductionCount / expectedProductionCount * 100`. Calculation is backend-owned; frontend never recalculates. Redis is cache-only — the calculation reads from Redis latest state but the alert entity records the snapshot values as source of truth.

**Alert lifecycle.** Alerts have three statuses: `OPEN`, `ACKNOWLEDGED`, `RESOLVED`. New alerts are always `OPEN`. Valid transitions: `OPEN → ACKNOWLEDGED`, `OPEN → RESOLVED` (SUPER_ADMIN only), `ACKNOWLEDGED → RESOLVED`. `RESOLVED` is terminal. Invalid transitions return `INVALID_STATE_TRANSITION`.

**Duplicate prevention.** Dedup key for alert creation: `(machineSparepartInstallationId, thresholdPercentage, active lifecycle)`. Only one non-resolved alert per installation+threshold is allowed. Duplicate telemetry that would re-trigger the same threshold does not create a second alert.

**Alert schema.** Alert entity records: machine reference, installation reference, threshold percentage, current counter snapshot at firing, consumed percentage at firing, traceId, status, status reason, actor (for transitions), and timestamps. Alert creation writes an audit event.

**Frontend evidence.** Alert detail must show `LifetimeProgress` — baseline counter, current counter, expected production count, consumed percentage, threshold. Status badges are non-color-only. All pages handle loading, empty, error, stale, read-only, and forbidden states.

**Installation view enrichment.** `InstallationView.currentCount`, `consumedProductionCount`, and `consumedPercentage` are currently hardcoded `null` in `MachineSparepartInstallationService.toView()`. Story 4.1 populates these from Redis latest state.

## Key Interfaces

- `CountingDeltaCalculator.delta(previous, current)` — 16-bit wrap-safe delta (already exists in `com.syncro.telemetry.application`)
- `RedisLatestTelemetryWriter.readCounting(machineId)` — returns `Optional<Long>` from Redis hash key `syncro:machine:{id}:latest` field `counting`
- `MachineSparepartInstallationEntity` fields: `baselineCounter` (long), `expectedProductionCount` (long), `thresholdPercentage` (int)
- `TelemetryPersistenceService.persist()` — called after Redis write; Story 4.1 hooks evaluation here
- `InstallationView` — `currentCount`, `consumedProductionCount`, `consumedPercentage` fields already declared, currently null

## Dependencies

- Epic 3 (all stories done): validated telemetry pipeline, `countingDelta` in Redis, `RedisLatestTelemetryWriter.readCounting()`, `CountingDeltaCalculator`
- Epic 2 story 2.6: `machine_sparepart_installations` table with `baseline_counter`, `expected_production_count`, `threshold_percentage`
- Epic 2 story 2.7: machine responsibility (needed by Epic 5 notification routing, not Epic 4)

## Story Dependencies (within Epic 4)

- Story 4.1 is the foundation — populates `InstallationView` live fields and introduces `SparepartLifetimeEvaluator`
- Story 4.2 depends on 4.1's evaluator to decide alert creation
- Stories 4.3–4.6 depend on the alert entity and lifecycle from 4.2
- Story 4.7 consumes the alert query service from 4.2/4.3 to surface state in Machine Hub and Operations Overview
- Epic 5 consumes the alert entity FK and lifecycle transitions from 4.2–4.6
