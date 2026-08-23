---
title: 'Deferred-work bundle 9: telemetry pipeline hygiene — separated alert-failure signal, batched Redis hydration'
type: 'refactor'
created: '2026-08-23'
baseline_revision: '1e66450'
final_revision: '4b1a96d'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/deferred-work.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** Two open deferred-work items degrade telemetry pipeline quality: (DW-37) `TelemetryPersistenceService.persist` wraps `evaluator.evaluateAll()` AND `alertService.evaluateAndCreateAlerts()` in ONE try/catch, so an evaluator failure silently skips alert creation with no distinct signal — operators cannot tell which stage broke; (DW-33) `MachineController.list` hydrates every machine's latest telemetry with one individual Redis `entries()` round trip per row — a 200-machine page issues up to 200 sequential HGETALLs on the dashboard hot path.

**Approach:** (DW-37) nest a dedicated try/catch around `alertService.evaluateAndCreateAlerts()` inside the existing evaluator try/catch with distinct structured log markers (`sparepart_lifetime_evaluation_failed` vs `sparepart_alert_evaluation_failed`); both remain non-fatal to persist. (DW-33) add `readLatestBatch(Collection<UUID>)` to `RedisLatestTelemetryWriter` using `executePipelined`, add `latestTelemetryBatch(Map<UUID, MachineStatus>)` to `LatestTelemetryQueryService` reusing the existing per-machine parsing (extracted into a shared private method), and switch only the LIST endpoint hydration to one pipelined call — single-machine endpoints keep the existing per-id path. Batch Redis failure logs one WARN and yields null telemetry for all rows in that response (matching today's per-read failure semantics).

## Boundaries & Constraints

**Always:**
- Preserve current non-fatal contract: neither evaluator nor alert-stage failure may fail the persist or roll back the Influx/Redis writes; telemetry-persisted INFO line still prints after either failure.
- Evaluator failure must short-circuit alert evaluation exactly as today (nested catch keeps this ordering).
- `readLatestBatch` must issue all HGETALLs in ONE pipelined connection round trip (`executePipelined`) and tolerate missing keys (absent hash -> absent/null entry, not error).
- Per-machine parsing/freshness semantics in batch mode must be byte-identical to the single path (reuse extracted parser; malformed receivedAt -> null for that machine only).
- Controller change limited to the list endpoint's hydration loop; GET-by-id and GET-by-code keep calling `latestTelemetry(...)`.
- Structured log keys follow the existing lowercase_snake marker convention seen in this service.

**Block If:**
- Pipelined execution cannot be exercised through the existing Mockito RedisTemplate seams without introducing new test frameworks → fall back to service/controller-level tests only and note it; HALT only if NO verification path exists (blocking condition `batch read unverifiable`).

**Never:**
- Do NOT change Influx write, dedupe SETNX, counting-delta, or counter-state logic.
- Do NOT alter LatestTelemetryDto shapes or API JSON contracts.
- Do NOT introduce caching/TTL layers around the batch read (DW-16 family is out of scope).
- Do NOT touch the webhook/notification packages in this bundle.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Evaluator throws | evaluateAll raises | WARN `sparepart_lifetime_evaluation_failed`; alert service NOT called; persist completes | Non-fatal |
| Alert stage throws | evaluateAll OK, evaluateAndCreateAlerts raises | WARN `sparepart_alert_evaluation_failed`; persist completes; persisted INFO still printed | Non-fatal |
| Batch healthy | 3 machines, 2 hashes present | One pipeline call; map has 2 parsed entries; third row null telemetry | No error |
| Batch redis down | executePipelined raises | Single WARN; ALL rows in response get null telemetry; HTTP 200 preserved | Non-fatal |
| Missing key in batch | 1 of 3 hashes expired | That machine null; others parsed normally | No error |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` -- split the shared catch block (~line 151-156) -- DW-37
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/RedisLatestTelemetryWriter.java` -- add `readLatestBatch` (pipelined) beside `readLatestAsMap` -- DW-33
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/LatestTelemetryQueryService.java` -- extract shared parser; add `latestTelemetryBatch` -- DW-33
- `syncro/apps/backend/src/main/java/com/syncro/machine/api/MachineController.java` -- list endpoint uses one batch call (~line 64-68) -- DW-33
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceServiceTest.java` -- extend: two distinct failure-signal tests -- DW-37
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/LatestTelemetryQueryServiceTest.java` -- extend: batch healthy/missing-key/redis-down -- DW-33
- `syncro/apps/backend/src/test/java/com/syncro/machine/api/MachineControllerTest.java` -- update list stubbing to batch method -- DW-33

## Tasks & Acceptance

**Execution:**
- [x] `TelemetryPersistenceService.java` -- nested dedicated catch for alert stage with distinct markers -- DW-37
- [x] `RedisLatestTelemetryWriter.java` -- `readLatestBatch` via `executePipelined` -- DW-33
- [x] `LatestTelemetryQueryService.java` -- extract `parseTelemetryData`; add `latestTelemetryBatch(Map<UUID, MachineStatus>)`; single-path delegates -- DW-33
- [x] `MachineController.java` -- list hydration via `latestTelemetryBatch` -- DW-33
- [ ] Tests (3 files above) -- extend per I/O matrix -- DW-37/DW-33

**Acceptance Criteria:**
- Given evaluateAll throws, when persist runs, then a lifetime-evaluation-specific WARN is logged, alert creation is skipped, and the method returns normally.
- Given evaluateAll succeeds but alert creation throws, when persist runs, then an alert-stage-specific WARN is logged (distinct from the evaluator marker) and the method returns normally.
- Given a list request over N machines, when the page renders, then exactly ONE pipelined Redis call replaces N sequential reads (verified by writer/service interaction test).
- Given some hashes absent/malformed in a batch, when parsing runs, then only those machines get null telemetry and the rest are fully populated.
- Given the Redis batch call fails, when the list endpoint handles it, then HTTP 200 with null telemetry rows and a single WARN (no per-row spam).
- Given `mvn test` over touched suites, when run, then all pass including pre-existing behavior tests.

## Spec Change Log

_Empty until first review loopback._

## Review Triage Log

### 2026-08-23 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 8 (low)
- defer: 0
- reject: 8 (Throwable/Error handling pre-existing and out of contract; unbounded-pipeline claim false — MachineService clamps size to MAX_PAGE_SIZE=200; marker rename IS the DW-37 deliverable; GET/LIST asymmetry documented design; inner-catch-throws theoretical; executePipelined result-count guaranteed by framework contract; id-set triple materialization micro-perf at <=200 rows; freshness-calculator blast radius identical to pre-change per-row path)
- addressed_findings:
  - `high` `patch` readLatestBatch pairing logic had ZERO direct tests — cross-wired machine telemetry would pass silently -> added writer-level tests: command-order pairing for two machines, non-Map/empty/null-element skipping
  - `medium` `patch` List.copyOf NPE on null elements + silent duplicate commands -> ids derived via filter(Objects::nonNull).distinct()
  - `medium` `patch` batch failure WARN logged only a count, useless for triage -> sampleIds (first 5) added to the log line
  - `low` `patch` alert-stage test accepted any() results argument -> evaluator return pinned via same(instance), asserting the actual collection is forwarded
  - `medium` `patch` controller list tests exercised only one row -> two-row page asserts each row hydrated from its own key
  - `medium` `patch` batch null-guard and malformed-receivedAt-in-batch branches untested -> both covered
  - `low` `patch` persisted-INFO guarantee existed only by inspection -> asserted via existing Logback appender helper in the alert-stage failure test

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="TelemetryPersistenceServiceTest,LatestTelemetryQueryServiceTest"` -- expected: all pass incl. new cases
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="MachineControllerTest"` -- expected: pass with batched stubbing
- `mvn -f syncro/apps/backend/pom.xml test-compile` -- expected: clean

**Manual checks (if no CLI):**
- Inspect diff: no DTO/API shape changes; batch failure path returns populated MachineView list with null telemetry fields.

## Auto Run Result

**Status:** done

**Summary:** Bundled two open deferred-work items (DW-37, DW-33) as telemetry pipeline hygiene: (DW-37) the shared evaluator/alert catch in `TelemetryPersistenceService.persist` was split into nested dedicated catches with distinct structured markers — `sparepart_lifetime_evaluation_failed` vs `sparepart_alert_evaluation_failed` — so operators can tell which stage broke; both stay non-fatal and the `mqtt_telemetry_persisted` INFO still prints after either failure (now test-enforced); (DW-33) machine LIST hydration switched from up-to-200 sequential Redis HGETALLs to ONE pipelined round trip: `RedisLatestTelemetryWriter.readLatestBatch` (executePipelined, null/duplicate-safe id handling) + `LatestTelemetryQueryService.latestTelemetryBatch` reusing the extracted per-machine parser with identical semantics, batch-level Redis failure degrading the page with a single WARN carrying sample ids. Single-machine GET endpoints keep the per-id path.

**Files changed:**
- `TelemetryPersistenceService.java` — nested dedicated alert-stage catch with distinct markers (DW-37)
- `RedisLatestTelemetryWriter.java` — `readLatestBatch` pipelined reader (DW-33)
- `LatestTelemetryQueryService.java` — extracted `parseTelemetryData`; `latestTelemetryBatch` (DW-33)
- `MachineController.java` — list hydration via one batch call (DW-33)
- 4 test files extended/new cases: distinct failure signals, persisted-INFO assertion, pairing/skip/null-element writer tests, batch parse/absent/malformed/failure service tests, two-row controller hydration

**Review findings breakdown:** 0 intent_gap, 0 bad_spec, 8 patches applied across 7 fix groups, 0 deferrals, 8 rejected as noise/pre-existing/spec-mandated.

**Follow-up review recommendation:** false — all patched findings low-severity after fixes; riskiest new code (pipelined pairing) now directly tested; four suites green.

**Verification performed:**
- `TelemetryPersistenceServiceTest` 26/26, `LatestTelemetryQueryServiceTest` 12/12, `MachineControllerTest` 21/21, `RedisLatestTelemetryWriterTest` 5/5 — all pass
- `test-compile` clean

**Residual risks:** GET-vs-LIST freshness asymmetry during a Redis blip is intentional (single WARN degrades whole list page); log marker rename retires the old combined message string (documented here as the operational contract change).
