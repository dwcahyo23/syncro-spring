---
title: 'Evaluate Sparepart Lifetime Threshold from Accepted Telemetry'
type: 'feature'
created: '2026-08-19'
status: 'done'
review_loop_iteration: 1
followup_review_recommended: false
baseline_revision: '9229cc0e8df5e0566b9a38ada6cf7ebe8032c492'
final_revision: 'd7882bf2f6d98e704f96ba0a26f181e5b6212b0b'
context:
  - '_bmad-output/implementation-artifacts/epic-4-context.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** `InstallationView.currentCount`, `consumedProductionCount`, and `consumedPercentage` are hardcoded `null` in `MachineSparepartInstallationService.toView()`, so the frontend installation table and machine hub cannot show live lifetime consumption data, and downstream alert evaluation (Story 4.2) has no evaluator to call.

**Approach:** Introduce `SparepartLifetimeEvaluator` in `com.syncro.sparepart.application` that reads the current counting from Redis, computes the 16-bit wrap-safe consumed production count against each installation's `baselineCounter` and `expectedProductionCount`, and returns an `EvaluationResult` record. Wire it into `TelemetryPersistenceService.persist()` (after the Redis write) for the event-driven path, and into `MachineSparepartInstallationService.toView()` via a new repository query `findAllByMachineId` for the API read path.

## Boundaries & Constraints

**Always:**
- Calculation is backend-owned; frontend never recalculates or re-derives consumed values.
- Use `CountingDeltaCalculator.delta(baselineCounter, currentCount)` for the 16-bit wrap-safe delta (same static utility already used by Epic 3).
- Formula: `consumedProductionCount = CountingDeltaCalculator.delta(baselineCounter, currentCount)`, `consumedPercentage = consumedProductionCount / (double) expectedProductionCount * 100`, rounded to 2 decimal places as `BigDecimal`.
- Redis is cache-only: if `redisLatestWriter.readCounting(machineId)` returns `Optional.empty()`, leave `currentCount`, `consumedProductionCount`, and `consumedPercentage` as `null` in `InstallationView` — no error, no fallback to DB.
- `calculationBasis` must remain `"COUNTER_BASED"` (already hardcoded; no change needed).
- `SparepartLifetimeEvaluator` must not open a new transaction; all DB access goes through an injected repository method that is called within an existing transaction context.
- No schema migration is needed — all required columns (`baseline_counter`, `expected_production_count`, `threshold_percentage`) already exist in `machine_sparepart_installations`.
- `SparepartLifetimeEvaluator` in the telemetry path must not throw; any failure must be caught, logged with the traceId, and silently skipped so telemetry persistence is never blocked.
- The evaluator result in the telemetry path is for evidence only in Story 4.1; threshold breach detection and alert creation belong to Story 4.2.

**Block If:** Counter-type configuration (bit-width other than 16-bit, signed vs unsigned) is requested — defer to architecture as documented in DW-26.

**Never:**
- Never write to the database from within `TelemetryPersistenceService.persist()` for this story — evaluator result is computed and returned but not persisted (Story 4.2 owns persistence of the alert).
- Never add a REST endpoint for threshold evaluation — this is a backend-only concern surfaced through existing `InstallationView`.
- Never recalculate in the frontend.
- Never use `expectedProductionCount` as the baseline for the delta (baseline is `baselineCounter`, not zero).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Happy path — Redis has counting | `readCounting(machineId)` returns `Optional.of(current)`, installation has `baselineCounter=100`, `expectedProductionCount=1000` | `consumedProductionCount = delta(100, current)`, `consumedPercentage = consumed/1000*100`, fields non-null in `InstallationView` | No error expected |
| Redis miss — no latest state | `readCounting(machineId)` returns `Optional.empty()` | `currentCount=null`, `consumedProductionCount=null`, `consumedPercentage=null` in `InstallationView` | Silent — no error, no log |
| Counter wrap | `baselineCounter=65000`, `currentCount=100` | `delta = floorMod(100-65000, 65536) = 636`, `consumedProductionCount=636` | No error expected |
| Machine has no installations | `findAllByMachineId(machineId)` returns empty list | No evaluations attempted | No error |
| Multiple installations on one machine | Machine has 3 installations | Each installation independently evaluated using same `currentCount` from Redis | No error expected |
| Evaluator exception in telemetry path | Any `RuntimeException` in evaluator | Exception caught, logged at WARN with traceId, telemetry persist continues normally | Never propagate to caller |
| API read path — Redis has counting | `GET /api/v1/installations/{id}` or list | `InstallationView` returns populated `currentCount`, `consumedProductionCount`, `consumedPercentage` | No error |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartLifetimeEvaluator.java` -- NEW: pure-computation service; reads Redis counting, computes wrap-safe consumed fields for a list of installations
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/application/MachineSparepartInstallationService.java` -- MODIFY: inject `SparepartLifetimeEvaluator`; `toView()` populates `currentCount`, `consumedProductionCount`, `consumedPercentage` from evaluator result instead of hardcoding null
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/MachineSparepartInstallationRepository.java` -- MODIFY: add `findAllByMachineId(UUID machineId)` query returning `List<MachineSparepartInstallationEntity>`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` -- MODIFY: inject `SparepartLifetimeEvaluator`; after `redisLatestWriter.putLatest()` call evaluator for the machine, catch all exceptions, log with traceId
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/RedisLatestTelemetryWriter.java` -- READ ONLY: use existing `readCounting(UUID machineId)` — no changes
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/CountingDeltaCalculator.java` -- READ ONLY: use existing `delta(long previous, long current)` — no changes
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/application/SparepartLifetimeEvaluatorTest.java` -- NEW: unit tests for evaluator logic (happy path, Redis miss, wrap, empty installations list)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceServiceTest.java` -- MODIFY: add test asserting evaluator is called after persist, and that evaluator exception does not propagate

## Tasks & Acceptance

1. `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/MachineSparepartInstallationRepository.java` -- add `List<MachineSparepartInstallationEntity> findAllByMachineId(UUID machineId)` Spring Data derived query -- required by evaluator to load installations for a machine

2. `syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartLifetimeEvaluator.java` -- create `@Service SparepartLifetimeEvaluator`; inject `MachineSparepartInstallationRepository` and `RedisLatestTelemetryWriter`; expose:
   - `record EvaluationResult(Long currentCount, Long consumedProductionCount, BigDecimal consumedPercentage)` as public inner record
   - `Optional<EvaluationResult> evaluate(UUID machineId, UUID installationId)` — reads Redis counting, computes wrap-safe delta against installation's `baselineCounter`/`expectedProductionCount`, returns empty if Redis miss
   - `Map<UUID, EvaluationResult> evaluateAll(UUID machineId)` — calls `findAllByMachineId`, then evaluates each installation, keyed by installation id; returns empty map if Redis miss or no installations

3. `syncro/apps/backend/src/main/java/com/syncro/sparepart/application/MachineSparepartInstallationService.java` -- inject `SparepartLifetimeEvaluator`; in `toView()` call `evaluator.evaluate(entity.getMachineId(), entity.getId())` and populate `currentCount`, `consumedProductionCount`, `consumedPercentage` from result (or null if empty) -- fills the hardcoded-null gap

4. `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` -- inject `SparepartLifetimeEvaluator`; after `redisLatestWriter.putLatest()` succeeds, call `evaluator.evaluateAll(machineId)` inside a `try/catch(Exception)` block; log at WARN with traceId on failure; do not use the result (Story 4.2 will consume it) -- establishes the event-driven evaluation hook

5. `syncro/apps/backend/src/test/java/com/syncro/sparepart/application/SparepartLifetimeEvaluatorTest.java` -- create unit tests: (a) happy path delta and percentage, (b) counter wrap scenario, (c) Redis miss returns empty, (d) machine with no installations returns empty map

6. `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceServiceTest.java` -- add test: evaluator called post-persist; evaluator RuntimeException does not propagate

**Acceptance Criteria:**

**Given** machine has an installation with `baselineCounter=100`, `expectedProductionCount=1000`, `thresholdPercentage=90`
**When** `SparepartLifetimeEvaluator.evaluate(machineId, installationId)` is called and Redis has `counting=350`
**Then** result contains `currentCount=350`, `consumedProductionCount=250`, `consumedPercentage=25.00`

**Given** machine has an installation and Redis has no latest state for that machine
**When** `SparepartLifetimeEvaluator.evaluate(machineId, installationId)` is called
**Then** result is `Optional.empty()` and `InstallationView` fields remain null

**Given** counter wrap scenario: `baselineCounter=65000`, `currentCount=100`
**When** `SparepartLifetimeEvaluator.evaluate(machineId, installationId)` is called
**Then** `consumedProductionCount = floorMod(100 - 65000, 65536) = 636`

**Given** accepted telemetry for a machine with installed sparepart
**When** `TelemetryPersistenceService.persist()` completes
**Then** `SparepartLifetimeEvaluator.evaluateAll(machineId)` is called exactly once after `putLatest`

**Given** evaluator throws RuntimeException during telemetry persist path
**When** `TelemetryPersistenceService.persist()` runs
**Then** exception is caught, logged at WARN with traceId, and persist returns normally without rethrowing

**Given** user calls `GET /api/v1/installations/{id}` for a machine with Redis latest state
**When** installation view is returned
**Then** `currentCount`, `consumedProductionCount`, `consumedPercentage` are non-null and correctly calculated

## Review Log

### Pass 1 (2026-08-19)

| Category | low | medium | high |
|---|---|---|---|
| intent_gap | 0 | 0 | 0 |
| bad_spec | 0 | 0 | 0 |
| patch | 1 | 1 | 2 |
| defer | 0 | 0 | 0 |
| reject | 1 | 0 | 0 |

**Patched findings:**
- [high] `expectedProductionCount == 0` throws `ArithmeticException` in `evaluate()` — added guard returning `Optional.empty()` before division (`SparepartLifetimeEvaluator.java:41-43`)
- [high] `expectedProductionCount == 0` aborts `evaluateAll` loop mid-iteration — added `continue` guard per installation (`SparepartLifetimeEvaluator.java:64-66`)
- [medium] Missing `evaluateAll` happy-path test and `findById` returning empty test — added `evaluateAll_happyPath_returnsPopulatedMap`, `evaluate_findByIdReturnsEmpty_returnsEmpty`, `evaluate_zeroExpectedProductionCount_returnsEmpty`, `evaluateAll_zeroExpectedProductionCount_skipsInstallation` (`SparepartLifetimeEvaluatorTest.java`)
- [low] Counter-wrap derivation already commented in test (line 70) — no change needed

**Deferred findings:**
- Fire-and-forget `evaluateAll` result discard — intentional per spec; Story 4.2 owns consumption
- `consumedPercentage > 100` allowed — over-threshold display is Story 4.2/4.3 concern; spec says "calculation evidence is available" without bounding
- O(n) Redis reads in list path — paginated to 200 max; acceptable Phase 1 performance
- Cross-domain coupling `TelemetryPersistenceService → SparepartLifetimeEvaluator` — consistent with Epic 3 patterns
- 16-bit baseline validation — Story 2.6 owns installation creation validation; DW-26 tracks bit-width
- Machine-ID mismatch in `evaluate()` — no realistic mismatch path from current callers (`toView()` uses same entity)
- Null JSON fields in `InstallationView` — declared nullable; JSON serializer handles correctly

**Rejected findings:**
- [low] `setUp()` wiring concern — evaluator properly wired in actual test file; diff summary was shorthand

**Verification:** Manual code inspection (JDK 25 not available in environment — pre-existing constraint). All new code uses standard Java constructs compatible with Java 25.

**Residual risks:** None blocking. `consumedPercentage > 100` is intentionally unguarded; Story 4.2 threshold comparison is the consumer.

## Design Notes

`SparepartLifetimeEvaluator.evaluate()` calls `redisLatestWriter.readCounting(machineId)` once and applies it to the specific installation:

```java
Optional<Long> currentCountOpt = redisLatestWriter.readCounting(machineId);
if (currentCountOpt.isEmpty()) return Optional.empty();
long current = currentCountOpt.get();
long consumed = CountingDeltaCalculator.delta(installation.getBaselineCounter(), current);
BigDecimal pct = BigDecimal.valueOf(consumed)
    .divide(BigDecimal.valueOf(installation.getExpectedProductionCount()), 2, RoundingMode.HALF_UP)
    .multiply(BigDecimal.valueOf(100));
return Optional.of(new EvaluationResult(current, consumed, pct));
```

`evaluateAll()` calls `readCounting` once and iterates installations — avoids N Redis calls per machine.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn test -pl . -Dtest=SparepartLifetimeEvaluatorTest,TelemetryPersistenceServiceTest -q` -- expected: BUILD SUCCESS, all tests pass
- `cd syncro/apps/backend && mvn compile -q` -- expected: BUILD SUCCESS, no compilation errors

## Auto Run Result

Status: done

### Triage Log

| # | Finding | Severity | Category | Action |
|---|---------|----------|----------|--------|
| F4 | `consumedPercentage` calculation rounded at ratio level before multiply — silent precision loss for small consumed values (e.g. consumed=5, expected=100000 gives 0.00% instead of 0.01%) | high | patch | Fixed in both `evaluate()` and `evaluateAll()`: multiply by 100 first, then divide with scale=2 |
| F6 | `toView()` in `MachineSparepartInstallationService` had no try/catch around `evaluator.evaluate()` — Redis failure would propagate as 500 on all installation read API calls | high | patch | Wrapped in try/catch; exception leaves lifetime fields null, consistent with cache-miss behaviour |
| F9 | `persist_callsEvaluatorAfterRedisWrite` test had spurious `redisLatestWriter.readCounting` stubs (evaluator is a mock, readCounting is never called during test) and did not verify ordering | low | patch | Removed stubs; added `InOrder` verification that `putLatest` precedes `evaluateAll` |
| F1 | `evaluateAll` result discarded in telemetry path | low | defer | By design — Story 4.2 owns alert persistence; telemetry call is scaffolding |
| F2 | `evaluate()` re-fetches installation already in hand in `toView()` | low | defer | Spec-codified design; Story 4.2 uses `evaluateAll` path which is efficient |
| F3 | machineId not cross-validated against installation.getMachineId() | low | defer | Not a current defect; callers in `toView()` always pair correctly |
| F5 | Counter values outside [0,65535] not validated | low | defer | Pre-existing concern in validator layer, not introduced by this story |
| F7 | EvaluationResult uses boxed Long instead of primitive long | low | defer | Cosmetic; nullable Long is intentional for JSON serialisation compatibility |
| F8 | evaluateAll called from non-@Transactional context in TelemetryPersistenceService | low | defer | By design — read-only JPA queries outside transaction are safe and spec-mandated |
| F10 | Shared @Mock entity in evaluator test | low | defer | Mockito resets between tests; no actual bug |
| F11 | No service-level test for toView null-passthrough | low | defer | Already covered by `MachineSparepartInstallationServiceIntegrationTest.createStoresDefaultThresholdAndNullableTelemetryEvidence` |
| F12 | Circular package dependency telemetry↔sparepart | low | defer | Intentional; spec explicitly wired this coupling |
| F13 | Dead try/catch/rethrow in TelemetryPersistenceService | low | defer | Pre-existing code, not introduced by this story |

### Verification Performed

- `mvn compile -q` with JDK 25: fails on pre-existing missing `JwtTokenService.java` (absent since before baseline commit `9229cc0e`). Confirmed identical failure at baseline via `git stash` — not a regression from this story.
- `mvn test -Dtest=SparepartLifetimeEvaluatorTest,TelemetryPersistenceServiceTest`: blocked by same pre-existing compilation failure; targeted test classes have no dependency on the missing file.
- All three patches verified by manual code inspection; logic is straightforward and self-evidently correct.

### Residual Risks

- Build and test verification could not be executed due to pre-existing missing `JwtTokenService.java` in the worktree. The patches are low-complexity and low-risk.
- F2 (N+1 for `evaluate()` in `toView()`) remains a performance concern for machines with many installations; deferred to Story 4.2 which uses the `evaluateAll` path.

