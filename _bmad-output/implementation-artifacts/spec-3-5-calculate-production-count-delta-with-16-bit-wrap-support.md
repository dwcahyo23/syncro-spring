---
title: '3-5 Calculate Production Count Delta with 16-Bit Wrap Support'
type: 'feature'
created: '2026-08-09'
baseline_revision: '28d45c29c23822f636c0b7d8fe761fa6352b02b6'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
final_revision: '65d20f6'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-3-context.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** The backend persists accepted telemetry but never computes the production-count delta between consecutive samples; a 16-bit unsigned counter that wraps from `65535` back to `0` would yield a negative-looking difference that downstream sparepart consumption (Epic 4) and the telemetry dashboard (Stories 3.7/3.8) must not be forced to reconstruct from raw counters.

**Approach:** A pure wrap-aware `CountingDeltaCalculator` computes the unsigned 16-bit delta. `TelemetryPersistenceService` reads the previous `counting` from the machine's Redis latest hash before overwriting it, computes the delta, and writes `countingDelta` into both the InfluxDB point (history) and the Redis latest hash (latest state) so the frontend never derives deltas from raw counters. `TelemetryPayload.parse` gains the negative-count rejection owned by this story (DW-15).

## Boundaries & Constraints

**Always:**
- Delta rule: `delta(previous, current) = Math.floorMod(current - previous, 1L << 16)` — exact for normal increase, single-wrap decrease, and boundary values; assumes at most one wrap between consecutive samples (standard 16-bit PLC sampling assumption).
- Previous-value source: the Redis latest hash field `counting` on `syncro:machine:{machineId}:latest`, read BEFORE the latest-state write. No previous value (first sample, or latest expired past `latestTtl`) → `countingDelta = 0`.
- `countingDelta` is an additive field on the Story 3.4 schema: InfluxDB `telemetry` point field (long, `i` suffix) and Redis latest hash field. Golden line protocol gains `countingDelta=N`.
- Negative `counting` is rejected by `TelemetryPayload.parse` with reason `out_of_range`, field `counting` (owns DW-15's negative-count half; `runtimeHours` negative validation still has no owning story).
- Delta is computed only for non-duplicate accepted messages — the existing dedupe gate order in `persist` is preserved.
- Tests: hermetic unit tests (calculator math, payload negative-count, persistence delta wiring, point field) plus integration tests with real Postgres + Redis + InfluxDB proving delta across consecutive persists including a wrap (canonical data `GM1`/`BF-08410`/`JBF19`).

**Block If:**
- If per-machine/configurable counter type (signed vs unsigned, bit width) is required to distinguish wrap from genuine counter decrease. Phase 1 has no such config; the uniform 16-bit unsigned wrap rule applies to all `counting` sources. Adding a configurable counter-type decision requires architecture input.

**Never:**
- No frontend changes; the frontend must never calculate production deltas from raw telemetry — the backend exposes `countingDelta` instead.
- No PostgreSQL migration/table for counter state; Redis latest hash is the previous-value source.
- No per-machine counter-type configuration; no change to `counting` type (`long`, integral, no floating point).
- No runtimeHours negative rejection (DW-15 remainder has no owning story).
- Do not compute or write a delta for duplicate messages; do not remove or alter existing Story 3.4 measurement/tag/field or latest-hash fields — only the additive `countingDelta`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| FIRST_SAMPLE | no previous `counting` in latest hash | `countingDelta` = 0 in InfluxDB point + latest hash | No error expected |
| NORMAL_INCREASE | previous=100, current=200 | `countingDelta` = 100 | No error expected |
| WRAP_MAX_TO_LOW | previous=65535, current=0 | `countingDelta` = 1 | No error expected |
| WRAP_MID_TO_LOW | previous=40000, current=1000 | `countingDelta` = 26536 | No error expected |
| COUNTING_ZERO_BOUNDARY | previous=0, current=65535 | `countingDelta` = 65535 | No error expected |
| NO_CHANGE | previous=100, current=100 | `countingDelta` = 0 | No error expected |
| NEGATIVE_COUNTING | payload `counting`=-1 | payload Rejected `out_of_range`/`counting`; no persist | Logged `mqtt_telemetry_rejected` |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/CountingDeltaCalculator.java` -- NEW: pure static wrap-aware delta (`UNSIGNED_16BIT_MODULUS = 1L << 16`)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPayload.java` -- MODIFY: reject `counting < 0` with `out_of_range`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` -- MODIFY: read previous counting, compute delta, thread `countingDelta` into point + latest hash
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/InfluxTelemetryWriter.java` -- MODIFY: `toPoint(...)` gains `countingDelta` param and adds the field
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/RedisLatestTelemetryWriter.java` -- MODIFY: add `readCounting(UUID)` returning `Optional<Long>` from latest hash
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/CountingDeltaCalculatorTest.java` -- NEW: wrap math unit tests
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPayloadTest.java` -- MODIFY: negative counting rejection
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/InfluxTelemetryWriterTest.java` -- MODIFY: assert `countingDelta` field; update `toPoint` call sites
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceServiceTest.java` -- MODIFY: delta wiring (no previous → 0, increase, wrap), `countingDelta` in latest hash fields
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceIntegrationTest.java` -- MODIFY: `3.5-DELTA-*` consecutive-persist tests; assert `countingDelta` in PERS-001/004
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryValidationIntegrationTest.java` -- MODIFY: `3.5-VAL-001` negative-count payload rejected end-to-end

## Tasks & Acceptance

**Execution:**
- [x] `CountingDeltaCalculator.java` -- static `delta(long previous, long current)` = `Math.floorMod(current - previous, 1L << 16)`; private ctor; `UNSIGNED_16BIT_MODULUS` constant -- pure, testable wrap rule
- [x] `TelemetryPayload.java` -- after integral/canConvertToLong checks, reject `countingNode.longValue() < 0` with `Rejected("out_of_range", "counting")` -- owns DW-15 negative-count AC
- [x] `RedisLatestTelemetryWriter.java` -- `Optional<Long> readCounting(UUID machineId)` reads latest hash field `counting` (empty if absent); extract shared latest-key helper -- previous-value source without a DB round-trip
- [x] `TelemetryPersistenceService.java` -- after dedupe gate acquisition: `previous = redisLatestWriter.readCounting(machineId)`; `delta = previous.map(p -> CountingDeltaCalculator.delta(p, current)).orElse(0L)`; pass `delta` to `toPoint` and add `countingDelta` to latest hash fields -- wrap-aware delta threading
- [x] `InfluxTelemetryWriter.java` -- `toPoint(payload, envelope, plantCode, machineCode, countingDelta)` adds `.addField("countingDelta", countingDelta)` -- additive history schema
- [x] `CountingDeltaCalculatorTest.java` -- cover normal increase, `65535→0`, mid→low wrap, `0→65535`, no-change, zero -- AC wrap-case coverage
- [x] `TelemetryPayloadTest.java` -- `counting:-1` → `out_of_range`/`counting`; negative integral double `-12.0` rejected too -- negative-count rejection lock
- [x] `InfluxTelemetryWriterTest.java` -- assert `countingDelta=0i` (and a non-zero value) in line protocol; update call sites -- schema lock
- [x] `TelemetryPersistenceServiceTest.java` -- no previous → delta 0; previous=65535, current=0 → 1; previous=100, current=200 → 100; latest-hash map contains `countingDelta` -- service wiring coverage
- [x] `TelemetryPersistenceIntegrationTest.java` -- `3.5-DELTA-001` first sample `countingDelta=0`, `3.5-DELTA-002` increasing → direct delta, `3.5-DELTA-003` `65535→0` wrap → 1 (real Redis+InfluxDB, consecutive persists on one machine); assert `countingDelta` in PERS-001/004 -- AC evidence
- [x] `TelemetryValidationIntegrationTest.java` -- `3.5-VAL-001` negative-count payload → `Result.Rejected` `out_of_range`/`counting` -- end-to-end rejection evidence
- [x] Run full backend suite -- no regression from Story 3.4 baseline (380 run, 0 failures, 1 pre-existing `SyncroBackendApplicationTests.contextLoads` AuditLogRepository error, 15 skipped)

**Acceptance Criteria:**
- Given a machine telemetry source uses an unsigned 16-bit counter, when current `counting` is lower than previous `counting` due to wrap, then the backend computes the delta with the unsigned wrap rule (`65535→0` = 1) — verified by `3.5-DELTA-003` + `CountingDeltaCalculatorTest`.
- Given current `counting` is higher than previous `counting`, then the backend computes the direct delta (current − previous) — verified by `3.5-DELTA-002` + `CountingDeltaCalculatorTest`.
- Given a payload with a negative or non-numeric `counting`, then the backend rejects it in payload validation — verified by `3.5-VAL-001` + `TelemetryPayloadTest`.
- Given test coverage, then it includes the wrap case from max range back to a low value — verified by `CountingDeltaCalculatorTest` + `3.5-DELTA-003`.
- Given the backend computes deltas, then the frontend does not calculate production delta from raw telemetry (backend exposes `countingDelta` in latest state and history) — verified by no frontend delta code existing and `countingDelta` present in Redis latest hash and InfluxDB point.

## Spec Change Log

## Spec Change Log

- 2026-08-09 (follow-up review pass, iteration 0): `RedisLatestTelemetryWriter.readCounting` now logs `mqtt_telemetry_latest_counting_malformed` (machineId + raw value) when the latest-hash `counting` field cannot be parsed, instead of silently treating it as no-previous; new `RedisLatestTelemetryWriterTest` locks the present/absent/malformed paths. No deferred-work ledger entries were re-opened or rewritten (DW-25/26/27 remain owned by the orchestrator).

- 2026-08-09 (review-1 pass, `in-review` → applied): patch fixes from the first review pass — `TelemetryPersistenceService.persist` computes the delta inside the existing InfluxDB try/catch so a `readCounting` failure deletes the owned dedupe key (no silent duplicate-suppression of an unpersisted message); `RedisLatestTelemetryWriter.readCounting` treats a malformed `counting` hash field as no-previous (delta 0) instead of throwing; `3.5-DELTA-003` additionally asserts the `countingDelta=1` InfluxDB field for the wrap message; first-sample unit test verifies `readCounting` is actually consulted (Mockito default no longer masks wiring); `mqtt_telemetry_persisted` logs `countingDelta=`. Deferred to the ledger: DW-26 (uniform 16-bit wrap rule cannot distinguish genuine decrease/reset/out-of-order delivery from a wrap — phantom-delta chain corruption until per-machine counter-type configuration or Story 3.9 ordering exists) and DW-27 (Redis latest-write failure after the point is persisted makes the next delta double-count the already-persisted span).

## Review Triage Log

### 2026-08-09 — Review pass (follow-up, fresh iteration 0)
- intent_gap: 0
- bad_spec: 0
- patch: 1 (low 1)
- defer: 0
- reject: 13 (medium 6, low 7)
- addressed_findings:
  - `[low]` `patch` F08: `RedisLatestTelemetryWriter.readCounting` silently swallowed a malformed `counting` hash field with zero observability (delta 0 forever with no operator signal). Added a structured `mqtt_telemetry_latest_counting_malformed` warn log (machineId + raw value) before returning empty, and a new `RedisLatestTelemetryWriterTest` covering the present/absent/malformed paths — which also closes the malformed-previous coverage gap flagged by F13.
  - Note: 7 rejected findings re-surface the already-deferred DW-25/26/27 root causes (gap/restart/flush continuity, wrap-vs-decrease ambiguity, Redis-latest-failure double-count); per orchestrator ownership these existing ledger entries were NOT re-opened or duplicated.

### 2026-08-09 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 5 (medium 1, low 4)
- defer: 2 (medium 2)
- reject: 4 (medium 1, low 3)
- addressed_findings:
  - `[medium]` `patch` BH-3/EH-1: `readCounting` was invoked outside the InfluxDB try/catch, so a Redis hiccup after the dedupe gate was acquired leaked the dedupe key and suppressed a broker redelivery as a duplicate without persisting anything. Moved the `readCounting`+delta computation inside the try so any failure deletes the owned dedupe key and rethrows (matching the existing InfluxDB-failure cleanup contract). Added `readCountingFailureDeletesOwnedDedupeKeyAndRethrows` regression test.
  - `[low]` `patch` BH-8: `3.5-DELTA-003` (the headline wrap case) asserted only the Redis latest hash; now also asserts the `countingDelta=1` field in the persisted InfluxDB point for the wrap message.
  - `[low]` `patch` BH-9/EH-2: `RedisLatestTelemetryWriter.readCounting` unguarded `Long.parseLong` on a malformed `counting` hash field threw `NumberFormatException` and aborted ingest; now caught and treated as no-previous (delta 0).
  - `[low]` `patch` BH-10: removed the redundant `lenient().when(readCounting→empty)` default (Mockito already returns `Optional.empty()`); the first-sample test now explicitly `verify`s `readCounting` was consulted, so a wiring regression that drops the delta computation is caught.
  - `[low]` `patch` BH-11: `mqtt_telemetry_persisted` log now includes `countingDelta=` so a phantom/spike delta can be correlated from the operational log without querying InfluxDB.

## Design Notes

- **Unsigned 16-bit delta:** `Math.floorMod(current - previous, 1L << 16)` yields the correct delta for every consecutive-sample case: normal increase (e.g. `200-100 → 100`), single wrap (`0 - 65535 = -65535 → floorMod = 1`), and boundary (`65535 - 0 → 65535`). It assumes at most one wrap between samples, the standard assumption for frequently-sampled PLC counters.
- **Previous-value source:** the Redis latest hash is the only previous-counter store in Phase 1; a single `HGET` is far cheaper than an InfluxDB read on the ingest path. First sample after latest expiry yields `delta = 0` (conservative — the sum of deltas still equals total production since the first observed sample). Continuity across gaps longer than `latestTtl` is deliberately not persisted; a durable counter-state store is deferred (see DW entry) rather than adding a PostgreSQL table in this story.
- **Additive schema:** golden write becomes `telemetry,plantCode=GM1,machineCode=BF-08410 running=true,runtimeHours=12.5,counting=100i,countingDelta=0i,traceId="<uuid>" <receivedAtEpochNanos>`. The Redis latest hash gains `countingDelta`. Both are backward-compatible additions to the Story 3.4 contract.
- **Uniform wrap rule:** all `counting` sources are treated as unsigned 16-bit wrap-capable; no per-machine counter-type config exists in Phase 1. `current < previous` is always a wrap. Architecture's "configured unsigned counter sources" becomes meaningful only when a counter-type configuration is introduced (deferred).
- **Negative count:** reason code `out_of_range` (stable, machine-readable) with field `counting`; satisfies the epic's "invalid negative/non-numeric count payloads are rejected by payload validation" and closes DW-15's negative-count half.

## Verification

**Commands:**
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="CountingDeltaCalculatorTest,TelemetryPayloadTest,InfluxTelemetryWriterTest,TelemetryPersistenceServiceTest"` -- expected: all pass (hermetic, no containers)
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="TelemetryPersistenceIntegrationTest,TelemetryValidationIntegrationTest"` -- expected: `3.5-DELTA-*` + `3.5-VAL-001` green (Testcontainers)
- `mvn -q -f syncro/apps/backend/pom.xml test` -- expected: full suite green, no regression from Story 3.4 baseline

**Manual checks (operator, requires broker + infra):**
- With `docker compose up -d` and backend on `local` profile, publish two consecutive payloads to `factory/GM1/BF-08410/telemetry` where the second `counting` wraps from `65535` to `0`; verify `countingDelta=1` in the latest Redis hash (`syncro:machine:{machineId}:latest`) and in the InfluxDB point (`influx query` on measurement `telemetry`).

## Auto Run Result

Status: done

- **Summary:** Follow-up review pass (iteration 0) on the completed Story 3-5. The story computes wrap-aware 16-bit unsigned production-count deltas on the telemetry ingest path via `CountingDeltaCalculator` (`Math.floorMod(current - previous, 1L << 16)`), reads the previous `counting` from the Redis latest hash before overwriting it (delta 0 for a first sample), persists `countingDelta` as an additive InfluxDB `telemetry` field and Redis latest-hash field, and rejects negative `counting` with `out_of_range`/`counting`. This pass applied one low patch: `RedisLatestTelemetryWriter.readCounting` now logs `mqtt_telemetry_latest_counting_malformed` (machineId + raw value) instead of silently swallowing a malformed `counting` hash field, and a new `RedisLatestTelemetryWriterTest` locks the present/absent/malformed paths.
- **Files changed:** `RedisLatestTelemetryWriter.java` (warn log on malformed previous-counting field), `RedisLatestTelemetryWriterTest.java` (new), plus this spec's change log / triage log / auto-run-result.
- **Review findings breakdown:** 1 patch applied (low: F08 malformed-field observability + F13 coverage lock); 0 deferred (7 surfaced findings re-surface already-deferred DW-25/26/27 and were NOT re-opened per orchestrator ownership); 13 rejected (6 medium, 7 low — wrap-vs-decrease/reset/out-of-order ambiguity, gap/restart/flush continuity, Redis-latest double-count, upper-bound scope, non-atomic read-then-write under concurrency, negative-from-Redis domain invariant, reason-code consistency for huge negatives, test-isolation/spacing, coverage nits, undocumented public precondition, log richness).
- **Follow-up review recommended:** false
- **Verification:** hermetic suite 34/34 green (including new `RedisLatestTelemetryWriterTest`, plus `CountingDeltaCalculatorTest`, `TelemetryPayloadTest`, `InfluxTelemetryWriterTest`, `TelemetryPersistenceServiceTest`); integration 14/14 green (`TelemetryPersistenceIntegrationTest`, `TelemetryValidationIntegrationTest` with real Testcontainers Postgres+Redis+InfluxDB) — no regression from the prior pass.
- **Residual risks:** unchanged from the prior pass — uniform 16-bit wrap rule treats any counter decrease (genuine reset, out-of-order delivery) as a wrap producing phantom near-max deltas until per-machine counter-type config or Story 3.9 ordering exists (DW-26); Redis latest-write failure after the point is persisted makes the next delta double-count the already-persisted span (DW-27); delta continuity is lost across gaps longer than `latestTtl`, restart, or flush (DW-25).

