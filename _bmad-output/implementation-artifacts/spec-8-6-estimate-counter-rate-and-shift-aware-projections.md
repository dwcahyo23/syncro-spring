---
title: 'Estimate Counter Rate and Shift-Aware Projections'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
baseline_commit: c97a859
final_revision: dce7798
context:
  - '{project-root}/_bmad-output/project-context.md'
warnings:
  - oversized
---

<intent-contract>

## Intent

**Problem:** FR-085/FR-086 (Epic 8, story 8.6) require shift-aware counter-rate estimation and calendar-time depletion projections so teams learn "how many days until replacement", but today only instantaneous counter-based consumption exists (Epic 4 evaluator); no production code reads InfluxDB history, no operating-calendar math exists, and nothing knows about lead time (8-2) or resolved shifts (8-5).

**Approach:** A new downstream `projection` module delivers `CounterRateEstimator` (rolling 30-day moving average of wrap-safe counting delta per effective operating hour from accepted InfluxDB telemetry, transparent full-history fallback under 30 days, explicit insufficient-data state) and `OperatingCalendarCalculator` (effective operating time from the resolved 8-5 shift windows, cross-midnight-safe, plant timezone `Asia/Jakarta` typed config applied only at the wall-clock boundary, math in UTC). Results are cached in Redis with an explicit TTL and invalidated on relevant writes via Spring events. A read-only endpoint `GET /api/v1/machines/{machineId}/sparepart-projections` (auth + plant scope, no job scope, never audited) serves per-installation depletion projections incl. expected counter consumption during the lead-time window when the installed sparepart has lead time. Frontend regenerates clients and adds a presentational `CounterRateProjectionCard` in the Machine Hub overview.

## Boundaries & Constraints

**Always:**
- Gate order for the projections READ: authentication required; non-SUPER_ADMIN needs `PlantScopeService.requirePlantAccess(user, machine.plant.id)` (403 `FORBIDDEN`, machine-module house convention); unknown machine → 404 `MACHINE_NOT_FOUND`. No job scope, no audit, no persisted state (computed stats are audit-free per house rule).
- Rate math is deterministic and backend-owned: samples = accepted telemetry points in the window ordered by time; totalDelta = Σ `CountingDeltaCalculator.delta(prev.counting, curr.counting)` over consecutive samples (wrap-safe, reuse story 3-5 calculator — never raw subtraction); operatingHours = `OperatingCalendarCalculator.effectiveOperatingHours(windowStart, windowEnd, resolvedWindows, zone)`; rate = totalDelta ÷ operatingHours, BigDecimal scale 2 HALF_UP.
- Basis selection: full 30-day coverage available → `ROLLING_30_DAY`; first sample newer than window start (fewer than 30 days of data) → transparent `FULL_HISTORY` fallback over [firstSample, now]; both expose window evidence (basis, windowStartAt, windowEndAt, firstSampleAt, lastSampleAt).
- Insufficient data is explicit, never guessed: `rateAvailable=false` + machine-readable `insufficientReason` (`NO_TELEMETRY`, `INSUFFICIENT_SAMPLES` (<2 samples), `NO_OPERATING_TIME` (zero operating hours in basis window), `STALE_DATA` (lastSample older than staleness threshold)) — and per-installation projections degrade to unavailable with the same reason; HTTP still 200.
- Per-installation projection (only when rateAvailable): remaining = max(0, expected − consumed) using the Epic-4 evaluator inputs; operatingHoursToDepletion = remaining ÷ rate; projectedDepletionAt = now + operatingHoursToDepletion converted to calendar time via dailyOperatingHours (= Σ shift durations; 0 → unavailable `NO_OPERATING_TIME`); `consumptionDuringLeadTime` = round(rate × leadTimeHours, 0 HALF_UP) shown ONLY when the sparepart has `leadTimeHours`; rounding scale 2 HALF_UP everywhere, timestamps UTC instants.
- Caching: Redis key `syncro:machine:{id}:projections` holding the serialized response with explicit TTL from typed config (`syncro.projection.cache-ttl`, default PT5M); invalidation on relevant writes via Spring `ApplicationEvent` `ProjectionCacheEvictionEvent(machineId|ALL)` published by `ShiftConfigService` (set/clear group+machine), `MachineSparepartInstallationService` (create/update/delete), and `SparepartService.updateProcurement` (lead-time edits → ALL); the projection module owns the `@EventListener` that evicts. Dependency direction preserved: projection depends on machine/sparepart/shiftconfig modules; mutators only depend on spring-context events.
- Config is a typed record `ProjectionProperties` (`syncro.projection.*`) following the `TelemetryProperties` pattern: `window-days=30`, `staleness=PT48H`, `cache-ttl=PT5M`, `plant-timezone="Asia/Jakarta"` (@DefaultValue + compact-ctor validation, yml keys present).
- Shift resolution for math reuses 8-5 storage verbatim: add a service-internal, no-auth method to `ShiftConfigService` (`resolveByMachineId(UUID)` → source + ordered LocalTime windows) so precedence logic has one owner; the projections endpoint still enforces user plant access itself.
- InfluxDB reading is NEW production surface: a `projection/infrastructure` reader issues parameterized SQL against measurement `telemetry` filtered by `"machineCode"` tag + time range (idiom proven in `TelemetryPersistenceIntegrationTest.queryPoints`), bounded by window start; reader failures surface as `STALE_DATA`-style unavailability with logged warn — never a 500, never guessed values.
- Frontend: `CounterRateProjectionCard` is presentational (health-card idiom: loading skeleton / error keep-last-known text / empty & insufficient states with literal reasons, severity Badge, `formatDateTimeUtc`); placed in Machine Hub Overview below `ShiftSection`, fed by the new generated GET hook keyed to the machine id; no domain math in the client.

**Block If:** Nothing requires human input. Pinned: rate unit is counting-per-OPERATING-hour; lead-time consumption treats `leadTimeHours` as OPERATING hours (consistent unit pairing, noted in DTO field doc); window/staleness/TTL/timezone all typed-config; projections endpoint lives in the projection module (embedding into `MachineView` rejected — would invert machine→sparepart dependency); event-based cache eviction (no cross-module direct calls, no SCAN).

**Never:**
- Never alter Epic-4 alert evaluation, percentage thresholds, or `InstallationView` lifetime fields; projections are additive evidence only.
- Never guess: no interpolation, no extrapolation beyond data, no default rates; missing/insufficient data is always the explicit unavailable state.
- Never write telemetry or projections to PostgreSQL; Redis holds only the cached response (rebuildable, never sole source of truth).
- Never require job scope or write audit rows for projections reads; never make the Influx reader block unbounded (bounded timeouts like the Garage client).
- Never reimplement shift precedence, wrap-delta, or freshness logic in the frontend or duplicate them across modules.
- Never hand-edit `src/lib/api/generated/**`; never add charting/date libraries — format durations/instants with existing helpers or plain Intl.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_RATE | ≥30d samples, shifts configured, operating hours >0 | 200; `rateAvailable=true`, `ROLLING_30_DAY`, window evidence, per-installation projections with `projectedDepletionAt` | No error |
| FULL_HISTORY_FALLBACK | First sample 10d ago | basis `FULL_HISTORY`, window=[firstSample, now], projections still computed | No error |
| NO_TELEMETRY | Zero samples in range | 200; `rateAvailable=false`, reason NO_TELEMETRY; installations unavailable | No error |
| INSUFFICIENT_SAMPLES | Exactly 1 sample | 200; reason INSUFFICIENT_SAMPLES | No error |
| STALE_DATA | Last sample older than staleness threshold | 200; reason STALE_DATA (freshness gate) | Reader failure also maps here + warn log |
| NO_OPERATING_TIME | Shifts empty (source NONE) or windows sum to 0h/day | 200; reason NO_OPERATING_TIME (rate and depletion unavailable) | No error |
| CROSS_MIDNIGHT_WINDOW | Shift 23:00–06:00 | Calendar math counts post-midnight hours on the correct day; dailyOperatingHours reflects it | No error |
| WRAP_AROUND_HISTORY | counting wraps past 65536 within window | totalDelta = Σ wrap-safe deltas (multiple wraps correct) | No error |
| DEPLETED_INSTALLATION | consumed ≥ expected | remaining clamps to 0; projectedDepletionAt = now (already depleted) | No error |
| NO_LEAD_TIME | Sparepart without leadTimeHours | Projection omits `consumptionDuringLeadTime` (null), rest intact | No error |
| WITH_LEAD_TIME | leadTimeHours=36.5 | `consumptionDuringLeadTime`=round(rate×36.5) present | No error |
| CACHE_HIT | Second GET within TTL | Same payload served from Redis; no Influx query | No error |
| EVICTION_ON_WRITE | Shift/installation/procurement mutation fires | Cached key evicted (event listened in projection module); next GET recomputes | No error |
| WRONG_PLANT | MANAGE (any scope) on other-plant machine | No computation | 403 FORBIDDEN |
| UNKNOWN_MACHINE | GET nonexistent id | Standard | 404 MACHINE_NOT_FOUND |

</intent-contract>

## Code Map

**Backend (`syncro/apps/backend/src/main/java/com/syncro`):**
- `resources/application.yml` -- MODIFY -- add `syncro.projection.{window-days,staleness,cache-ttl,plant-timezone}` (defaults via @DefaultValue; yml documents them).
- `config/ProjectionProperties.java` -- NEW -- record pattern clone of `TelemetryProperties` (@DefaultValue, compact-ctor validation with key-named messages; timezone via `ZoneId.of`, validated).
- `projection/infrastructure/InfluxTelemetryHistoryReader.java` -- NEW -- first production Influx read: parameterized SQL over measurement `telemetry` by `"machineCode"` tag + time range, returns ordered `(Instant, long counting)` samples; bounded attempt/total timeouts (Garage-client precedent); wraps errors in nested reader exception.
- `projection/infrastructure/ProjectionRedisCache.java` -- NEW -- get/put/evict(evictMachine, evictAll) on key `syncro:machine:{id}:projections` with TTL from properties (StringRedisTemplate; `RedisLatestTelemetryWriter` style).
- `projection/application/OperatingCalendarCalculator.java` -- NEW -- pure static-ish component: `dailyOperatingHours(windows)`, `effectiveOperatingHours(Instant start, Instant end, List<ShiftWindowCommand>, ZoneId)`; converts local windows to UTC intervals per day; handles cross-midnight (end ≤ start wraps next day); zero-length guarded upstream.
- `projection/application/CounterRateEstimator.java` -- NEW -- deps reader, calendar, ProjectionProperties, Clock; produces `RateEstimate(basis, windowStart/End, first/lastSampleAt, totalDelta, operatingHours, ratePerOperatingHour, insufficientReason)` per Boundaries rules.
- `projection/application/SparepartProjectionService.java` -- NEW -- deps `MachineRepository.findByIdWithPlantAndGroup`, `MachineSparepartInstallationRepository.findAllByMachineId`, `SparepartRepository` (leadTimeHours fetch), `ShiftConfigService.resolveByMachineId`, estimator, cache, Clock; builds the view; Redis look-aside (get → miss → compute → put).
- `projection/application/ProjectionCacheEvictionListener.java` -- NEW -- `@EventListener(ProjectionCacheEvictionEvent)`; machineId present → delete that key; null machineId (ALL) → delete keys matching `syncro:machine:*:projections` via `StringRedisTemplate.keys(pattern)` (dev-scale safe).
- `projection/api/ProjectionDtos.java` + `ProjectionController.java` + `ProjectionExceptionHandler.java` -- NEW -- `GET /api/v1/machines/{machineId}/sparepart-projections` (operationId `getMachineSparepartProjections`), DTOs: `MachineSparepartProjectionsView(machineId, rateAvailable, ratePerOperatingHour?, calculationBasis?, windowStartAt?, windowEndAt?, firstSampleAt?, lastSampleAt?, insufficientReason?, shiftSource, dailyOperatingHours?, projections[])`, `InstallationProjection(installationId, sparepartId, functionName, available, reason?, remainingCounters?, projectedDepletionAt?, leadTimeHours?, consumptionDuringLeadTime?)`; handler 400 INVALID_PATH_VALUE / 403 FORBIDDEN / 404 MACHINE_NOT_FOUND, @Content on all non-2xx.
- `shiftconfig/application/ShiftConfigService.java` -- MODIFY -- add `public MachineShiftConfigView resolveByMachineId(UUID machineId)` (no auth; reuses resolveMachineConfig internals).
- Event: `projection/application/ProjectionCacheEvictionEvent.java` (record UUID machineId nullable) -- publishers: `ShiftConfigService` (set/clear group+machine → machine id; group set/clear → machines of that group via `MachineRepository.findAllByMachineGroupId`… if absent, publish ALL), `MachineSparepartInstallationService` (create/update/delete → machineId), `SparepartService.updateProcurement` (publish ALL). Publishers depend only on spring-context.
- Tests: `src/test/.../projection/OperatingCalendarCalculatorTest.java` (controlled fixed instants; cross-midnight, DST-free zone, zero-window, multi-day span), `CounterRateEstimatorTest.java` (pure Mockito reader + fixed clock: basis selection, wrap sums, staleness, insufficient reasons), `SparepartProjectionServiceIntegrationTest.java` (Testcontainers Postgres+Redis+InfluxDB cloning `TelemetryPersistenceIntegrationTest` containers; write real points then assert rate/projections/cache-hit/eviction/denials), `ProjectionControllerTest.java` (WebMvc status+$.code matrix).

**Frontend (`syncro/apps/web/src`):**
- `lib/api/generated/**` -- REGENERATE -- hook `useGetMachineSparepartProjections` + models (`machineSparepartProjectionsView`, `installationProjection`).
- `components/syncro/counter-rate-projection-card.tsx` -- NEW -- presentational health-card idiom: props `{ data?: MachineSparepartProjectionsView; isLoading?; error? }`; renders rate tile (value + unit "counters/op-hour"), basis badge + window evidence lines, per-installation table rows (function name, remaining, projected date via `formatDateTimeUtc`, lead-time consumption when present), insufficient-state text with literal reason; loading/error/empty branches.
- `features/machine-hub/machine-hub-page-content.tsx` -- MODIFY -- render `<CounterRateProjectionCard machineId={machine?.id}>` in Overview TabsContent after `<ShiftSection>`; card self-fetches via generated hook (enabled on id).
- Tests: `components/syncro/counter-rate-projection-card.test.tsx` -- NEW -- fixture-driven (data-quality-panel pattern: no network mocks; cases: happy w/ lead-time column, insufficient reasons, fallback basis, depleted, loading/error); extend `features/machine-hub/machine-hub-page-content.test.tsx` (card stubbed/rendered below ShiftSection).

## Tasks & Acceptance

**Execution:**
- [x] `ProjectionProperties` + yml keys + `ShiftConfigService.resolveByMachineId` -- config and shared shift resolution.
- [x] `OperatingCalendarCalculator` + controlled-clock unit tests (cross-midnight, multi-day, zero-window) -- operating-time math.
- [x] `InfluxTelemetryHistoryReader` (bounded) + `ProjectionRedisCache` -- first production history read + cache.
- [x] `CounterRateEstimator` + unit tests (basis selection, wrap sums, staleness, insufficient reasons) -- estimation core.
- [x] `SparepartProjectionService` + eviction listener + publisher wiring in ShiftConfig/Installation/Procurement mutations -- orchestration + cache lifecycle.
- [x] DTOs + controller + exception handler -- read-only API surface.
- [x] Backend tests per matrix (integration w/ Testcontainers Postgres+Redis+Influx; WebMvc) -- prove every row.
- [x] Orval regeneration (+force-add orphaned models) -- typed hooks exist.
- [x] `CounterRateProjectionCard` + hub integration + fixture tests -- UI delivery.
- [x] Verify: Maven targeted suite green; web vitest/tsc/biome green; live stack evidence (insufficient-data on clean machine, then with published pilot telemetry: rate+basis+depletion+lead-time consumption, Redis key with TTL, eviction on shift write).

**Acceptance Criteria:**

- Given accepted telemetry spanning ≥30 days and a resolved shift config, when projections are requested, then the rate equals wrap-safe Σdelta ÷ effective operating hours for the window (basis `ROLLING_30_DAY`) with window evidence attached. [AC 8.6-1]
- Given fewer than 30 days of history, when requested, then the estimate transparently falls back to `FULL_HISTORY` over [firstSample, now] with the same formula; with none/insufficient/stale/no-operating-time data the response is 200 with `rateAvailable=false` and a specific `insufficientReason` rather than a guessed value. [AC 8.6-2]
- Given a rate exists for an installed sparepart, when projected, then the response contains remaining counters, calendar `projectedDepletionAt` derived through daily operating hours (cross-midnight windows counted correctly), and — only when the sparepart has lead time — expected `consumptionDuringLeadTime` = rate × leadTimeHours. [AC 8.6-3]
- Given two GETs within the cache TTL, when the second completes, then it is served from the Redis key with explicit TTL; a shift-config, installation, or procurement write evicts the affected machine's cached entry so the next GET recomputes. [AC 8.6-4]
- Given the Machine Hub overview, when opened for a machine, then `CounterRateProjectionCard` shows rate, basis/window evidence, per-installation depletion dates, lead-time consumption when applicable, and explicit insufficient-data states; loading/error/empty branches follow the house card idiom. [AC 8.6-5]
- Given a user without plant access or an unknown machine id, when requesting projections, then the API returns 403 `FORBIDDEN` / 404 `MACHINE_NOT_FOUND` with no computation; reads are never audited and never mutate alert evaluation. [AC 8.6-6]

## Spec Change Log

- 2026-08-24: Spec created (draft → ready-for-dev → in-progress). Epic 8 context cache valid; story 8.6 ACs from epics.md (1304-1321), FR-085/086 + §7.11 estimation rules from PRD, architecture modules/API lines re-read. Codebase explored via subagent — decisive facts: NO production Influx read exists (only write path + test-only query idiom), wrap-safe `CountingDeltaCalculator` reusable, no operating-calendar math anywhere, installations carry baseline/expected/threshold + `findAllByMachineId`, sparepart has `leadTimeHours` BigDecimal, shift config service/entities from 8-5 with source resolution, Redis latest-writer key/TTL idioms, Testcontainers triple-container pattern (Postgres+Redis+InfluxDB3) proven in TelemetryPersistenceIntegrationTest, hub Overview tab embeds after ShiftSection, health-card/data-quality-panel presentation+fixture-test idioms. Pinned: new downstream `projection` module owning estimator+calendar+reader+cache+endpoint (embedding into MachineView rejected — dependency inversion); dedicated endpoint `/machines/{id}/sparepart-projections`; rate = Σ consecutive wrap-safe deltas ÷ effective operating hours; basis ROLLING_30_DAY/FULL_HISTORY + explicit insufficientReason taxonomy (NO_TELEMETRY/INSUFFICIENT_SAMPLES/NO_OPERATING_TIME/STALE_DATA); lead-time consumption treats leadTimeHours as operating hours; typed ProjectionProperties (window-days/staleness/cache-ttl/plant-timezone Asia/Jakarta); Redis look-aside cache key `syncro:machine:{id}:projections` TTL PT5M with event-based eviction (machineId or ALL via keys pattern) published by ShiftConfig/Installation/Procurement mutations; internal no-auth `ShiftConfigService.resolveByMachineId` reused for precedence; reads never audited.

## Review Triage Log

### 2026-08-24 — Review pass (Blind Hunter + Edge Case Hunter, baseline c97a859)
- intent_gap: 0
- bad_spec: 0
- patch: 11: (high 1, medium 3, low 7) -- all fixed in this pass:
  - `[high]` `[patch]` A zero/tiny rate reached `remaining ÷ rate` as an uncaught ArithmeticException → 500 for an idle-but-fresh machine; estimator now computes at internal scale 6, gates on the exact value, and introduces explicit reason NO_PRODUCTION_DELTA (enum + DTO + card copy + Orval regen); depletion math divides window totals instead of the rounded display rate.
  - `[medium]` `[patch]` Cache eviction ran inside the mutating transaction, letting a concurrent GET re-cache a stale view until TTL; listener is now `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` (house pattern from NotificationRoutingService).
  - `[medium]` `[patch]` Telemetry ingestion had no invalidation path, so fresh counters were hidden behind the PT5M TTL; accepted persists now publish a machine-scoped eviction event.
  - `[medium]` `[patch]` Coverage gaps closed: integration case for lead-time-null sparepart (SVC-007) and an Influx visibility await helper against eventual-read lag.
  - `[low]` `[patch]` ShiftConfigService.MachineNotFoundException escaping resolveByMachine mapped to 404 instead of a raw 500.
  - `[low]` `[patch]` Overlapping shift windows double-counted hours; calculator now unions intervals before summing (daily + effective), with overlap tests.
  - `[low]` `[patch]` Read path dropped its pointless readOnly transaction spanning Redis/Influx I/O and reuses one machine fetch via `resolveByMachine(machine)` overload.
  - `[low]` `[patch]` Reader javadoc now states why literal SQL is used (v3 queryPoints has no bind params) with quote-escaping retained.
  - `[low]` `[patch]` ProjectionDtos contract doc corrected to actual nullability and documents daily-average approximation + single plant timezone.
  - `[low]` `[patch]` Cache mapper tolerates unknown properties so deploys don't poison cached entries.
  - `[low]` `[patch]` Hub DOM-ordering assertion no longer silently skips when the card selector misses (loud expect-first); formatter-only biome fixes applied.
- defer: 2: (medium 1, low 1)
  - `[medium]` Counter RESET vs genuine 16-bit wrap is indistinguishable from baseline>lastSample and fabricates consumption — pre-existing across Epic-4 lifetime math too; needs a product-level reset policy → deferred-work.md.
  - `[low]` ALL-eviction uses Redis KEYS pattern; switch to SCAN iteration before multi-tenant scale → deferred-work.md.
- reject: 5: (medium 2, low 3)
  - Daily-average depletion can land outside shift windows (spec-pinned approximation, now documented on the DTO).
  - Numerator counts all wall-clock deltas vs shift-only denominator (spec-pinned formula; overestimation risk documented).
  - Reader failures surface as STALE_DATA (spec-pinned degradation; warn log distinguishes).
  - Single hardcoded plant timezone (architecture-pinned typed config default for the single-site pilot).
  - "Group edits should evict per machine" — implementation matches the spec Code Map pin (ALL via keys pattern); scaling concern deferred.
- addressed_findings: all 11 patches fixed and re-verified — backend targeted suite 107/107 BUILD SUCCESS (incl. new RATE-002, calculator union tests, SVC-007), Orval regenerated with NO_PRODUCTION_DELTA enums, frontend 263/263 vitest, tsc exit 0, biome infos-only; live stack re-checked post-fix (insufficient state explicit, Redis TTL=300, eviction on shift PUT).

## Design Notes

- **Why a dedicated projections endpoint instead of embedding in `MachineView`:** architecture mentions "embedded in machine-hub read responses", but embedding forces machine→sparepart dependency inversion (installations + lead time live in the sparepart module). The hub Overview tab composing the card preserves the intended UX with clean dependency direction (hub page is composition root).
- **Why Σ consecutive wrap-safe deltas:** a single `delta(first,last)` breaks when counting wraps >1× within the window; consecutive summation reuses the exact story 3-5 semantics and is unit-testable.
- **Why operating-hours denominator:** rate must survive shift schedules (a machine running 1 shift/day halves counters/day vs 24/7); converting to calendar time uses `dailyOperatingHours` so depletion dates reflect the resolved calendar (the entire point of 8-5).
- **Golden example:** shifts [{07:00–15:00},{23:00–06:00}] ⇒ dailyOperatingHours=15.0; window totalDelta=4500 over 20 operating days ⇒ rate=4500/(20×15)=15.00/op-h; installation remaining=2700 ⇒ 180 op-hours ⇒ 12 calendar days ⇒ projectedAt=now+12d; leadTimeHours=36.5 ⇒ consumptionDuringLeadTime=round(15×36.5)=548.
- **Continuity:** reuses `CountingDeltaCalculator`, `TelemetryProperties` config idiom, Testcontainers triple-container pattern, health-card presentation idiom, and 8-5 shift entities/service. Alert path untouched (8-7 will consume these estimates later).

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=Projection*,CounterRate*,OperatingCalendar*,ShiftConfig*,TelemetryPersistenceIntegrationTest,SparepartLifetimeEvaluator*"` -- expected: BUILD SUCCESS (estimator/calendar/projection coverage incl. cross-midnight and cache behavior).
- `cd syncro/apps/web && npm run generate:snapshot && npm run generate:api && npm run test:unit` -- expected: projection hooks generated; suite green.
- `npx tsc --noEmit`; `npx biome check <touched files>` -- expected exit 0 / no new diagnostics.

**Manual checks:**
- Boot docker stack + backend; login; `GET /api/v1/machines/{id}/sparepart-projections` on pilot machine BF-08410: expect explicit insufficient-data (no history) → then publish pilot MQTT counting payloads (story 7-3 script) and re-request: rate + basis + depletion + lead-time fields appear; `redis-cli TTL syncro:machine:{id}:projections` ≈ 300; PUT a shift-config change and confirm the key is evicted.

## Auto Run Result

Status: done (final_revision dce7798; baseline c97a859).

**Summary:** Machines now expose `GET /api/v1/machines/{id}/sparepart-projections`: a wrap-safe rolling 30-day counter-rate per effective operating hour (transparent FULL_HISTORY fallback, explicit insufficient states incl. NO_PRODUCTION_DELTA), per-installation remaining counters, calendar depletion instants derived from the resolved shift calendar (cross-midnight-safe, overlap-unioned), and expected consumption during the sparepart's lead-time window. Results are look-aside cached in Redis (PT5M TTL) and evicted via AFTER_COMMIT events on telemetry/shift/installation/procurement writes. Machine Hub overview renders CounterRateProjectionCard with basis badge, window evidence, and insufficient-reason copy.

**Files changed:** backend — new projection module (config, calendar, estimator, Influx history reader, Redis cache, eviction event/listener, service, controller/DTOs/handler), ShiftConfigService internal resolver + publisher hooks, installation/sparepart publishers, repository fetch-join, application.yml; frontend — CounterRateProjectionCard (+tests), hub wiring (+test), Orval regen with 5 force-added model files.

**Review findings breakdown:** 11 patches applied (1 high zero-rate 500 → explicit NO_PRODUCTION_DELTA state; 3 medium: AFTER_COMMIT eviction, telemetry-write invalidation, integration coverage gaps; 7 low incl. overlap union, exception mapping, tx/fetch hygiene, DTO doc corrections, cache-mapper leniency, loud ordering assertion), 2 deferred (counter-reset vs wrap policy — pre-existing Epic-4-wide; KEYS→SCAN at scale), 5 rejected as spec/architecture-pinned.

**Verification performed:** targeted backend suite 107/107 BUILD SUCCESS (estimator/calendar/integration/WebMvc incl. new cases); live stack evidence — BF-08410 GET returned explicit STALE_DATA view, Redis key TTL=300s, shift PUT evicted key and recompute reflected shiftSource MACHINE + dailyOperatingHours 8.0, override cleaned up 204; frontend vitest 263/263, tsc exit 0, biome infos-only.

**Residual risks:** daily-average depletion may land outside shift windows (documented approximation); single plant timezone per deployment (pilot-pinned); counter-reset fabrication deferred pending product policy; Redis ALL-eviction uses KEYS (dev-scale).
