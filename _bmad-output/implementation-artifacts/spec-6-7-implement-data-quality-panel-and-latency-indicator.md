---
title: 'Implement Data Quality Panel and Latency Indicator'
type: 'feature'
created: '2026-08-22'
status: 'done'
review_loop_iteration: 1
followup_review_recommended: false
baseline_commit: 0bbfc50
context: []
warnings: []
---

<intent-contract>

## Intent

**Problem:** The SUPER_ADMIN health dashboard shows dependency/worker health, freshness, and the quarantine log, but has no aggregate data-quality view (quarantine rate, anomalies, dead-lettered messages over a time window) and no end-to-end telemetry latency signal. An operator cannot tell whether data quality is degrading (SM-009: quarantine/rejection rate must stay below 1%) or whether the publish→visible path is slowing down (SM-008: telemetry visible within 5 seconds of MQTT publish) without manually counting quarantine rows.

**Approach:** Backend-owned data-quality snapshot + two shared frontend components. (1) A new in-memory `TelemetryDataQualityTracker` records windowed accept/quarantine/anomaly/dead-letter events on the existing ingest path plus the last publish→persisted latency; a new `TelemetryDataQualityService` derives per-metric severities (page-spec 4.4 thresholds) and a latency state (normal <5s / elevated 5–15s / critical >15s); a new SUPER_ADMIN-only `GET /api/v1/telemetry/data-quality` returns one `TelemetryDataQualityStatus` payload. (2) The frontend renders a shared `DataQualityPanel` (quarantined count, rejection rate %, anomaly count, dead-letter count, window, per-metric severity text badges, "View Quarantine Log" in-page drill-down link) in a new Data Quality section and a shared `LatencyIndicator` in the page header, both rendering backend-provided values verbatim.

## Boundaries & Constraints

**Always:**
- All quality/latency values and severity states are backend-computed; the frontend renders verbatim and never re-derives thresholds, rates, or states.
- Follow the `TelemetryIngestTracker` observability philosophy already established in this module: tracker values are in-memory, reset on restart, never a source of truth; document this in the new tracker's javadoc and in the status record javadoc (same wording pattern as `TelemetryFreshnessStatus`).
- New endpoint is SUPER_ADMIN-only, guarded exactly like `TelemetryFreshnessController` (including the `user == null ||` null-principal FORBIDDEN guard); never exposed on unauthenticated `/actuator/health`.
- Use the injected `Clock` in all new backend code and fixed `Clock` in tests (never `Clock.systemUTC()` / `Instant.now()` in tests).
- Metric/latency thresholds are product targets, not deployment knobs: quarantine >10 warning / >50 critical, rejection rate >1% warning / >5% critical, anomaly >0 warning, dead-letter >0 critical, latency ≥5s elevated / >15s critical (page-spec 4.4 + epic-6 context; SM-008/SM-009) — encode as named constants in the service, each citing its source.
- The metrics window (default 1 hour, page-spec 4.4 "default: last 1 hour") is the one new config surface: a validated `syncro.telemetry.data-quality.window` duration in the existing typed `TelemetryProperties` record style; do not add scattered `@Value` reads.
- Frontend reuses the established health-hook pattern (queryKey, 30s refetch via `SYSTEM_HEALTH_REFRESH_INTERVAL_MS`, retry 2, 401 → `expireAuthSession`, `createHealthRequestSignal`) and the operational-status type conventions in `features/system-health/types`.
- Status communication must not be color-only: every severity badge carries a text label; the latency indicator shows the state name, not just a color/icon.
- `DataQualityPanel` and `LatencyIndicator` are shared domain components → `src/components/syncro/` (architecture "Domain components" list names both), free of feature imports except types passed via props.
- Timestamps ISO-8601 UTC; rate/latency formatting is display-only and deterministic (rate: `toFixed(2)` + `%`; latency: `<1000ms → "N ms"`, else seconds with one decimal).

**Block If:** No decisions require human input for this story.

**Never:**
- No DB migrations, new infra services, or new frontend dependencies/libraries.
- Do not change existing endpoint contracts (`/api/v1/telemetry/freshness`, `/api/v1/telemetry/ingest/status`, `/api/v1/telemetry/quarantine`, `/actuator/health`), `computeOverallBanner` behavior, or any 6.4–6.6 UI semantics — the data-quality panel and latency indicator are additive surfaces and do NOT feed the overall banner.
- Do not change ingest validation semantics, quarantine persistence, dedupe, or the bounded-queue backpressure design (3.11/3.12); the tracker only observes outcomes.
- Do not use unbounded memory for windowing (no per-event timestamp lists): bounded minute-bucket ring counters sized from the configured window.
- Do not log or expose payload contents, device identifiers beyond what the quarantine log already shows, secrets, or credentials in the new payload/UI.
- Do not implement a retry/dead-letter queue: dead-letter is an observability counter for terminal ingest failures only (the ingest path has no retry by design; the handler catch is terminal).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Healthy traffic in window | accepted 1000, quarantined 2 (non-anomaly), dead-letter 0, latency 800ms | `quarantinedCount=2`, `rejectionRatePct=0.20`, `anomalyCount=0`, `deadLetterCount=0`, `receivedCount=1002`, `lastLatencyMs=800`, `latencyState=NORMAL`, status GOOD/SUCCESS | No error |
| No traffic yet / after restart | tracker empty (restart resets) | All counts 0, `rejectionRatePct=0.0`, `receivedCount=0`, `lastLatencyMs=null`, `latencyState=NO_DATA`, status GOOD/SUCCESS with reason null | No error; panel renders zero-state, latency shows "No data" |
| Anomaly present | quarantine reason `out_of_range` in window | `anomalyCount` incremented alongside `quarantinedCount`; `anomalySeverity=WARNING` (threshold >0) | Other reasons do NOT count as anomaly |
| Dead-letter (terminal ingest failure) | handler catch triggered (e.g. Influx/Redis persist failure) | `deadLetterCount` ≥1, `deadLetterSeverity=CRITICAL` (>0), overall status CRITICAL | Counter recording must never throw from the catch path |
| Rejection rate boundary | rate exactly 1% → OK; >1% → WARNING; >5% → CRITICAL | Deterministic severity per constants; test pins the boundaries | Rate undefined when `receivedCount=0` → report 0.0, never NaN/÷0 |
| Quarantine count boundary | 10 → OK; 11 → WARNING; 50 → OK-warning; 51 → CRITICAL | Per page-spec 4.4 thresholds (">10", ">50") | — |
| Latency boundary | 4999ms NORMAL; 5000ms ELEVATED (5–15s band inclusive); 15000ms ELEVATED; 15001ms CRITICAL | ≥5s elevated, >15s critical (band 5–15s inclusive per AC) | — |
| Device clock ahead of server (negative latency) | `payload.timestamp` after server now | Clamped to 0ms, counted as NORMAL sample; never negative | Documented conservative clamp |
| Old events age out | events older than window (minute-bucket eviction) | Excluded from counts/rate; quarantine log table remains the durable history (panel is process-local window) | No error |
| Latency sample not yet taken | validation accepted but persist not completed (or dedupe duplicate suppressed before persist) | `lastLatencyMs` unchanged (no sample recorded for duplicates / failures) | — |
| Not authorized | MANAGE/VIEWER or anonymous on `/api/v1/telemetry/data-quality` | HTTP 403 / 401 | Controller guard throws FORBIDDEN; null principal → 403, never 500 |
| Data-quality fetch fails in UI | backend down / 500 | Panel shows visible error line; LatencyIndicator shows error state text | Page still renders; refresh path includes the new query |
| Malformed payload | fetcher shape guard rejects (missing/typed-wrong fields) | Query error, visible error state; never renders `undefined` values | Fetcher throws descriptive error |

</intent-contract>

## Code Map

**Backend (all under `syncro/apps/backend/src/main/java/com/syncro/telemetry/`):**

- `application/TelemetryIngestTracker.java` — existing in-memory observability pattern (accepted counter, restart-reset philosophy, synchronized record + volatile read) that the new tracker mirrors; do not modify unless a shared helper is genuinely warranted.
- `application/TelemetryDataQualityTracker.java` -- NEW -- thread-safe in-memory tracker: minute-bucket ring counters (accepted, quarantined, anomaly, deadLetter) sized from the configured window, `lastLatencyMs` volatile (clamped ≥0); methods `recordAccepted()`, `recordQuarantined(String reason)` (anomaly iff reason equals the `out_of_range` constant), `recordDeadLettered()`, `recordLatencyMs(long)`; `snapshot` accessors returning windowed counts + rate inputs + last latency.
- `application/TelemetryDataQualityState.java` -- NEW -- enum GOOD/DEGRADED/CRITICAL with `statusLabel()`/`statusSeverity()` (architecture severity strings, local constants — same style as `TelemetryFreshnessState`, no health-module dependency).
- `application/LatencyState.java` -- NEW -- enum NO_DATA/NORMAL/ELEVATED/CRITICAL with `statusLabel()`/`statusSeverity()` (NEUTRAL/SUCCESS/WARNING/CRITICAL).
- `application/TelemetryDataQualityStatus.java` -- NEW -- operational-contract record: `status, statusLabel, statusSeverity, statusReason, timestamp` + `windowSeconds, quarantinedCount, rejectionRatePct, anomalyCount, deadLetterCount, receivedCount, quarantinedSeverity, rejectionRateSeverity, anomalySeverity, deadLetterSeverity, lastLatencyMs (Long, nullable), latencyState, latencySeverity`.
- `application/TelemetryDataQualityService.java` -- NEW -- injects tracker + `TelemetryProperties` + `Clock`; threshold constants (page-spec 4.4, SM-008) with source citations; derives per-metric severities, overall status = worst severity, rate = quarantined/(accepted+quarantined)×100 (0.0 when received=0), latency state from `lastLatencyMs` vs 5s/15s constants.
- `api/TelemetryDataQualityController.java` -- NEW -- `GET /api/v1/telemetry/data-quality`, SUPER_ADMIN guard mirroring `TelemetryFreshnessController` exactly (null-principal check included), OpenAPI annotations, `@Tag(name = "telemetry-data-quality")`.
- `application/MqttTelemetryIngestHandler.java` -- MODIFY -- inject `TelemetryDataQualityTracker`; Accepted branch → `recordAccepted()`; Rejected branch → `recordQuarantined(rejected.reason())`; catch block → `recordDeadLettered()` before logging (counter calls must not throw).
- `application/TelemetryPersistenceService.java` -- MODIFY -- inject `Clock` + tracker; immediately after successful `redisLatestWriter.putLatest(...)` (inside the same try, before `counterStateRepo.save`) → `tracker.recordLatencyMs(Duration.between(accepted.payload().timestamp(), Instant.now(clock)).toMillis())`. Not recorded on dedupe-duplicate early return, not recorded when putLatest throws.
- `com/syncro/config/TelemetryProperties.java` (module `config`) -- MODIFY -- add nested `DataQuality` record `window` (`@DefaultValue("PT1H")`), compact constructor validation positive; wire as `@DefaultValue`-created `dataQuality` component of the parent record (follows the existing `Ingest` nesting/validation style).

**Backend tests (under `syncro/apps/backend/src/test/java/com/syncro/telemetry/`):**

- `application/TelemetryIngestTrackerTest.java` — existing fixed-clock tracker test style to mirror.
- `application/TelemetryDataQualityTrackerTest.java` -- NEW -- fixed clock: counters accumulate; minute-bucket eviction excludes old events; anomaly separated from generic quarantine (`out_of_range` vs other reason); latency last-value wins + negative clamps to 0; window boundary (event exactly at window edge).
- `application/TelemetryDataQualityServiceTest.java` -- NEW -- fixed clock + stub tracker: rate math incl. received=0 → 0.0; all metric threshold boundaries (quarantine 10/11/50/51, rate 1%/5% exclusive, anomaly 0/1, dead-letter 0/1); latency boundaries 4999/5000/15000/15001; NO_DATA when lastLatencyMs null; overall = worst severity; reason string populated when not GOOD.
- `api/TelemetryDataQualityControllerTest.java` -- NEW -- `@WebMvcTest` mirroring `TelemetryFreshnessControllerTest`: SUPER_ADMIN 200 with full JSON shape (all fields present, nulls where allowed); MANAGE/VIEWER 403; unauthenticated 401; null-principal-tolerant guard.
- `application/MqttTelemetryIngestHandlerTest.java` -- MODIFY -- verify tracker calls: accept → recordAccepted; reject → recordQuarantined with reason; handler failure → recordDeadLettered (existing test fixtures `AcceptingTelemetryValidationService` / `RejectingTelemetryValidationService`).
- `application/TelemetryPersistenceServiceTest.java` -- MODIFY -- verify `recordLatencyMs` after successful putLatest (assert measured duration uses payload.timestamp → clock), not called on dedupe duplicate, not called when putLatest throws.

**Frontend (all under `syncro/apps/web/src/`):**

- `features/system-health/types/index.ts` -- MODIFY -- add `TelemetryDataQualityState = "GOOD" | "DEGRADED" | "CRITICAL"`, `LatencyState = "NO_DATA" | "NORMAL" | "ELEVATED" | "CRITICAL"`, `TelemetryDataQualityStatus` mirroring the backend record (nullable `lastLatencyMs`, `statusReason`).
- `features/system-health/hooks/use-data-quality.ts` -- NEW -- `fetchDataQuality` (GET `/api/v1/telemetry/data-quality`, auth header, `createHealthRequestSignal`, 401 → `expireAuthSession`, full shape guard incl. enum values, counts finite, severity strings, nullable latency) + `useDataQuality` (queryKey `["telemetry-data-quality"]`, `SYSTEM_HEALTH_REFRESH_INTERVAL_MS`, retry 2).
- `features/system-health/hooks/use-data-quality.test.ts` -- NEW -- 200 resolves payload; 500 rejects; 401 expires session; malformed shape (bad enum / missing count / non-numeric rate) rejects.
- `components/syncro/data-quality-panel.tsx` -- NEW -- shared panel (server-safe markup, no hooks): header row "Data Quality" + overall status text badge; metric rows Quarantined / Rejection rate / Anomaly / Dead-letter each with value + severity text badge (OK/Warning/Critical — never color-only); Window row formatted from `windowSeconds` (3600 → "last 1 hour", else "last N minutes"); "View Quarantine Log" link (`<a href="#telemetry-quarantine-log">`, Next `Link` acceptable); loading skeleton, error line, zero/null-safe values (never `undefined`/`NaN`).
- `components/syncro/latency-indicator.tsx` -- NEW -- shared compact indicator: "Latency" label + formatted value (`<1000ms → "N ms"`, else `X.Ys`; null + NO_DATA → "No data") + state text label + severity icon; error → "Latency unavailable"; visually distinct elevated/critical via the established severity token classes (border/bg) AND text.
- `features/system-health/components/system-health-page.tsx` -- MODIFY -- add `useDataQuality`; render `LatencyIndicator` in the page header (right of title, per page-spec 4.1); new "Data Quality" section between Workers and Telemetry Freshness rendering `DataQualityPanel`; add `id="telemetry-quarantine-log"` + `scroll-mt` to the existing quarantine section heading; include the new query in `dataUpdatedAts`, `isLoading`, `isFetching`, `handleRefresh`; banner inputs unchanged.
- `features/system-health/components/system-health-page.test.tsx` -- MODIFY -- add `dataQualityQuery` mock + fixtures; tests: panel renders all five metric rows + window; severity text badge visible for warning fixture; "View Quarantine Log" link points to `#telemetry-quarantine-log` and the anchor exists; LatencyIndicator renders value + state in header for normal/elevated/critical/no-data; data-quality error renders visible error without crashing the page; refresh includes the new query refetch; loading state renders skeleton.
- `components/syncro/health-card.tsx` — read-only reference for severity token classes/`deriveSeverity`; do not modify.

## Tasks & Acceptance

**Execution:**

- [x] `syncro/apps/backend/.../telemetry/application/TelemetryDataQualityTracker.java` -- NEW -- thread-safe minute-bucket ring counters (accepted/quarantined/anomaly/deadLetter) sized from the configured window (bounded memory, no per-event lists), `lastLatencyMs` volatile clamped ≥0; javadoc states the in-memory/restart-reset/observability-only philosophy and the anomaly reason constant (`out_of_range`). [AC 6.7-1, AC 6.7-2]
- [x] `syncro/apps/backend/.../telemetry/application/TelemetryDataQualityState.java` + `LatencyState.java` -- NEW -- enums with label/severity in the `TelemetryFreshnessState` style (local constants, no cross-module imports). [AC 6.7-1, AC 6.7-2]
- [x] `syncro/apps/backend/.../telemetry/application/TelemetryDataQualityStatus.java` -- NEW -- operational-contract record per Code Map; javadoc documents restart-reset semantics of every count and the latency definition (payload publish timestamp → Redis latest visible). [AC 6.7-1, AC 6.7-2]
- [x] `syncro/apps/backend/.../telemetry/application/TelemetryDataQualityService.java` -- NEW -- severity derivation with named threshold constants citing page-spec 4.4 / SM-008 / epic-6 context; rate math with received=0 → 0.0; worst-severity overall; latency state ≥5s ELEVATED, >15s CRITICAL; reason text listing the triggered conditions (or null when GOOD). [AC 6.7-1, AC 6.7-2]
- [x] `syncro/apps/backend/.../telemetry/api/TelemetryDataQualityController.java` -- NEW -- SUPER_ADMIN-only `GET /api/v1/telemetry/data-quality` mirroring `TelemetryFreshnessController` (null-principal guard, OpenAPI, tag). [AC 6.7-1]
- [x] `syncro/apps/backend/.../config/TelemetryProperties.java` -- MODIFY -- add validated nested `dataQuality.window` (default PT1H) following the existing record-nesting validation style. [AC 6.7-1]
- [x] `syncro/apps/backend/.../telemetry/application/MqttTelemetryIngestHandler.java` -- MODIFY -- record accept/quarantine/dead-letter outcomes to the tracker at the three existing branches; tracker failures must not alter ingest behavior. [AC 6.7-1]
- [x] `syncro/apps/backend/.../telemetry/application/TelemetryPersistenceService.java` -- MODIFY -- inject `Clock` + tracker; record publish→visible latency immediately after successful `putLatest` only (not on dedupe duplicate, not on Redis failure). [AC 6.7-2]
- [x] `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryDataQualityTrackerTest.java` -- NEW -- per Code Map (fixed clock, eviction, anomaly split, clamp, window edge). [AC 6.7-1, AC 6.7-2]
- [x] `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryDataQualityServiceTest.java` -- NEW -- threshold boundaries, rate math incl. ÷0 guard, latency boundaries, NO_DATA, worst-severity rollup. [AC 6.7-1, AC 6.7-2]
- [x] `syncro/apps/backend/src/test/java/com/syncro/telemetry/api/TelemetryDataQualityControllerTest.java` -- NEW -- `@WebMvcTest` role matrix + full JSON shape assertions. [AC 6.7-1]
- [x] `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestHandlerTest.java` -- MODIFY -- verify tracker outcome recording on accept/reject/failure paths. [AC 6.7-1]
- [x] `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceServiceTest.java` -- MODIFY -- verify latency recorded only after successful latest-write (payload.timestamp → injected clock), not on duplicate/failure. [AC 6.7-2]
- [x] `syncro/apps/web/src/features/system-health/types/index.ts` -- MODIFY -- add the three new types mirroring the backend record. [AC 6.7-1, AC 6.7-2]
- [x] `syncro/apps/web/src/features/system-health/hooks/use-data-quality.ts` + `.test.ts` -- NEW -- fetcher/hook per Code Map with full shape guard; tests for 200/500/401/malformed. [AC 6.7-1, AC 6.7-2]
- [x] `syncro/apps/web/src/components/syncro/data-quality-panel.tsx` -- NEW -- shared panel per Code Map (five metric rows + per-metric severity text badges + window + drill-down link + loading/error/zero states). [AC 6.7-1, AC 6.7-3]
- [x] `syncro/apps/web/src/components/syncro/latency-indicator.tsx` -- NEW -- shared indicator per Code Map (value + state label, elevated/critical visually distinct AND text-labeled, no-data/error states). [AC 6.7-2, AC 6.7-3]
- [x] `syncro/apps/web/src/features/system-health/components/system-health-page.tsx` -- MODIFY -- header LatencyIndicator; Data Quality section; quarantine section anchor `id="telemetry-quarantine-log"`; wire the query into stale-banner/refresh/loading/fetching aggregations; banner untouched. [AC 6.7-1, AC 6.7-2, AC 6.7-3, AC 6.7-4]
- [x] `syncro/apps/web/src/features/system-health/components/system-health-page.test.tsx` -- MODIFY -- per Code Map (panel rows, badges, anchor link + target, latency states, error/refresh/loading coverage). [AC 6.7-1, AC 6.7-2, AC 6.7-3, AC 6.7-4]
- [x] Verify: run `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="TelemetryDataQualityTrackerTest,TelemetryDataQualityServiceTest,TelemetryDataQualityControllerTest,MqttTelemetryIngestHandlerTest,TelemetryPersistenceServiceTest"` → BUILD SUCCESS; from `syncro/apps/web` run `npm run test:unit`, `npm run build`, and `npx biome lint` on changed files (clean; CRLF formatter diffs are the known environmental baseline). [all ACs]

**Acceptance Criteria:**

- Given SUPER_ADMIN opens System Health, when the health dashboard loads, then `DataQualityPanel` shows quarantined message count, rejection rate %, anomaly count, dead-letter count, and time window (backend-computed over the configured window). [AC 6.7-1]
- Given telemetry is flowing (or absent), when the dashboard loads, then `LatencyIndicator` shows the current end-to-end publish→visible latency with backend-computed state: normal (<5s), elevated (5–15s), critical (>15s), or no-data before the first sample. [AC 6.7-2]
- Given elevated or critical latency (or warning/critical quality metrics), when the components render, then the states are visually distinct AND communicated with text labels, not color alone. [AC 6.7-3]
- Given the Data Quality panel renders, then it links to the quarantine log (`QuarantineLogTable` section) for detail drill-down via an in-page anchor. [AC 6.7-4]

### Review Findings

3-layer adversarial review (Blind Hunter, Edge Case Hunter, Acceptance Auditor) on 2026-08-22, resuming an interrupted first run whose patches were half-applied. All `patch` findings below are applied and verified; 1 deferred (DW-72), 9 dismissed as noise / documented-by-design (global synchronized monitor tradeoff, non-atomic snapshot, O(window) scan per 30s poll, NEUTRAL badge — a real backend contract value, null-principal test parity with the mirrored controller, constructor-arity blast radius — resolved by whole-module compilation, far-future-timestamp clamp — pinned by test and spec-constrained, `role="status"` re-announce — established pattern, stale dev verification record — regenerated truthfully below).

- [x] [Review][Patch] Backend did not compile: the half-applied `reason()` change compared a `String` to `double` thresholds (`rate > REJECTION_RATE_CRITICAL_PCT`) [TelemetryDataQualityService.java:148]
- [x] [Review][Patch] Latency sample never expired — a stalled pipeline reported the last latency/state forever; the sample now carries its minute and ages out with the window to NO_DATA (blind+edge, MAJOR both) [TelemetryDataQualityTracker.java]
- [x] [Review][Patch] `in`-based severity badge lookup resolved prototype keys ("toString"/"constructor") to Object.prototype members → render crash; now `Object.hasOwn` [data-quality-panel.tsx:38]
- [x] [Review][Patch] Fetcher guard accepted any string for the six severity fields; now validates SUCCESS/WARNING/CRITICAL/NEUTRAL membership [use-data-quality.ts]
- [x] [Review][Patch] Fetcher guard admitted negative/fractional `lastLatencyMs`; now requires null or a non-negative integer [use-data-quality.ts]
- [x] [Review][Patch] Effective-window derivation duplicated between service and tracker; the tracker now owns `effectiveWindowSeconds()` [TelemetryDataQualityTracker.java + TelemetryDataQualityService.java]
- [x] [Review][Patch] Exactly-5% rejection-rate boundary unpinned and reason text never asserted; both added (5% exact stays WARNING) [TelemetryDataQualityServiceTest.java]
- [x] [Review][Patch] Reason text could contradict the severity at display rounding (raw 1.004% → "rejection rate 1.00% above 1.0%"); reason now formats the raw rate `%.4f` [TelemetryDataQualityService.java]
- [x] [Review][Patch] 30-day window cap compared minutes so PT30D-plus-seconds slipped through; now compared in seconds, with a new `TelemetryPropertiesTest` covering zero/negative/null/30d-exact/30d+1s [TelemetryProperties.java + TelemetryPropertiesTest.java]
- [x] [Review][Patch] LatencyIndicator rendered contradictory "unavailable · No data" on first-load failure and duplicated "No data · No data"; the trailing label is suppressed when it duplicates the value or the state is unknown [latency-indicator.tsx]
- [x] [Review][Patch] Logback DEBUG level set in handler tests without try/finally (leaked to later tests on assertion failure); all three attach/detach sites wrapped [MqttTelemetryIngestHandlerTest.java]
- [x] [Review][Patch] Zero concurrency coverage for the tracker's synchronized claim/reset protocol; added a 4-thread × 2-round hammer test across a minute boundary asserting exact totals [TelemetryDataQualityTrackerTest.java]
- [x] [Review][Patch] Loading-skeleton page test required by the Code Map was missing; added [system-health-page.test.tsx]
- [x] [Review][Patch] Window label rendered every whole-hour multiple as hours (7200 → "last 2 hours"), deviating from the spec letter "3600 → 'last 1 hour', else minutes"; now spec-literal [data-quality-panel.tsx]
- [x] [Review][Patch] Panel zero-state, error-with-stale-data, proto-key-severity fallback, and latency value-formatting boundaries were untested; new `data-quality-panel.test.tsx` + `latency-indicator.test.tsx`
- [x] [Review][Patch] Tracker javadoc now documents the two known blind spots (pre-validation throws counted nowhere; quarantine counted before the durable write can transiently diverge from the log) [TelemetryDataQualityTracker.java]
- [x] [Review][Patch] `TelemetryPayload.java` (shared `REASON_OUT_OF_RANGE` constant) was missing from the File List; manifest corrected below
- [x] [Review][Defer] System-clock jumps (NTP step) silently reset or mis-attribute windowed counts [TelemetryDataQualityTracker.java] — deferred, pre-existing wall-clock windowing property → DW-72

## Spec Change Log

- 2026-08-22: Spec created (draft → ready-for-dev). Ultimate context engine analysis completed — comprehensive developer guide created.
- 2026-08-22: Implemented (see Dev Agent Record). All 20 tasks complete; status → review.
- 2026-08-22: Code review (3-layer adversarial) — 17 patches applied incl. a compile fix from an interrupted first review run, 1 deferral (DW-72), 9 dismissed; all gates re-run green; status → done.

## Design Notes

- **Metric definitions grounded in existing pipeline semantics:** quarantined = validation rejections recorded by the quarantine branch (`unknown_machine`, `malformed_topic`, `invalid_timestamp`, …); anomaly = the subset with reason `out_of_range` (runtimeHours/counting outside plausible range — page-spec 4.4 "Values outside plausible range"); dead-letter = terminal ingest failures in the handler catch (validation passed but processing threw; the ingest path has no retry by design, so "exhausted retries" from page-spec 4.4 maps to terminal failure); rejection rate = quarantined / (accepted + quarantined) × 100 (SM-009's "quarantine/rejection rate"). Accepted = validation-passed messages (dedupe-suppressed duplicates included on purpose — they were received and validated).
- **Why in-memory windowed counters instead of counting the quarantine table:** the rate needs a windowed denominator (accepted messages) that has no durable per-message table; mixing durable quarantine counts with in-memory accepted counts would produce a 100% rejection rate after every restart until traffic resumes. All four counters share one in-memory window so the panel is self-consistent; the durable `QuarantineLogTable` remains the drill-down history. This exactly extends the documented `TelemetryIngestTracker` philosophy ("in-memory, reset on restart, observability only, never a source of truth").
- **Latency definition:** payload `timestamp` (device-declared publish time, already a validated contract field) → the instant the Redis latest write succeeds (the moment dashboards can observe the message). Queue wait, validation, and persist time are included; the browser poll interval (≤30s) is not — SM-008's 5s budget is enforced against the platform portion the backend owns. Negative samples (device clock ahead) clamp to 0ms rather than surfacing skew as fake latency. The sample is recorded in `TelemetryPersistenceService` right after `putLatest` succeeds, so dedupe duplicates (early return) and Redis failures (throw) never produce samples.
- **Thresholds are product targets, not config:** page-spec 4.4 pins quarantine >10/>50, rate >1%/>5%, anomaly >0, dead-letter >0; epic-6 context pins latency 5s/15s. They are named constants citing sources (mirroring `TelemetryFreshnessCalculator`'s documented hardcoded thresholds). Only the window is configurable (`syncro.telemetry.data-quality.window`, default 1h) because it sizes the tracker ring and is legitimately deployment-specific.
- **Bounded windowing:** minute-bucket ring counters (one `AtomicLongArray` per category indexed by minute-of-epoch mod ring size) keep memory constant regardless of message volume — consistent with the 3-12 bounded-queue mindset; no per-event timestamp buffers. Counts are exact at minute granularity; the ring covers the window plus boundary slack.
- **Banner intentionally untouched:** `computeOverallBanner` keeps its 6.4/6.5 semantics (stale-machines were likewise excluded in 6.6). Data quality is a panel-level surface; feeding the banner would change established behavior and is not required by the ACs.
- **Component placement:** `DataQualityPanel`/`LatencyIndicator` go to `src/components/syncro/` because the architecture's domain-component list names both; they receive the status type via props (type-only import from the feature is the established boundary, same as `QuarantineLogTable` importing `QuarantineEntryView` from the feature hook).
- **Drill-down is an in-page anchor:** the quarantine log already lives on this page (6.4/6.6); page-spec 4.8 says "Accessible from DataQualityPanel 'View Quarantine Log' link" — an `#telemetry-quarantine-log` anchor satisfies it without a new route; the section heading gains the id (+ scroll margin).

## Verification

**Commands:**
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="TelemetryDataQualityTrackerTest,TelemetryDataQualityServiceTest,TelemetryDataQualityControllerTest,MqttTelemetryIngestHandlerTest,TelemetryPersistenceServiceTest"` -- expected: BUILD SUCCESS (hermetic, no containers).
- `npm run test:unit` (from `syncro/apps/web`) -- expected: all unit tests pass including new data-quality/latency tests.
- `npm run build` (from `syncro/apps/web`) -- expected: production build succeeds, `/dashboard/system-health` compiles.
- `npx biome lint` on changed frontend files -- expected: clean (CRLF formatter-only diffs under `npm run check` are the known environmental baseline).

**Manual checks (if no CLI):**
- Inspect panel renders for a healthy fixture (all rows, zero-state, window label) and a degraded fixture (warning/critical text badges, dead-letter critical); LatencyIndicator normal/elevated/critical/no-data states. Full-stack browser verification may not be runnable unattended; if infra is unavailable, rely on the unit/build gates and state that explicitly.

## Dev Agent Record

### Agent Model Used

GLM-5.3 (ZCode, builtin:zai-start-plan/GLM-5.3)

### Debug Log References

- Backend scoped runs: `mvn -f syncro/apps/backend/pom.xml test -Dtest="TelemetryDataQualityTrackerTest,TelemetryDataQualityServiceTest,TelemetryDataQualityControllerTest,MqttTelemetryIngestHandlerTest,TelemetryPersistenceServiceTest,IngestWorkerStatusServiceTest,TelemetryFreshnessServiceTest,TelemetryIngestQueueConfigTest"`.
- Frontend: `npx vitest run` scoped files, `npm run test:unit`, `npm run build`, `npx biome lint` on changed files.

### Completion Notes List

- All four panel metrics + latency come from one in-memory `TelemetryDataQualityTracker` (minute-bucket ring counters sized from `syncro.telemetry.data-quality.window`, default PT1H), recorded at the three existing ingest outcomes: `recordAccepted()` on the Accepted branch, `recordQuarantined(reason)` on the Rejected branch (anomaly iff reason `out_of_range`), `recordDeadLettered()` in the handler catch. Tracker increment claims a bucket under `synchronized` so a ring-wrap reset can never race an increment and lose it; reads are lock-free.
- Latency is sampled in `TelemetryPersistenceService` immediately after the Redis latest write succeeds: `Duration.between(payload.timestamp, Instant.now(clock))`, clamped ≥0 in the tracker (device clock ahead of server → 0ms). Dedupe duplicates (early return) and Redis write failures never sample; verified by dedicated tests including the negative-clamp case.
- `GET /api/v1/telemetry/data-quality` mirrors the `TelemetryFreshnessController` guard exactly (null-principal → 403); role matrix tested (@WebMvcTest): SUPER_ADMIN 200 (full JSON shape incl. null `lastLatencyMs` for NO_DATA), MANAGE/VIEWER 403, anonymous 401.
- Frontend: shared `DataQualityPanel` (components/syncro) renders the five metric rows with per-metric text-labeled severity badges, overall label+badge, window ("last 1 hour"), reason line, and the "View Quarantine Log" in-page anchor link; the quarantine section gained `id="telemetry-quarantine-log"`. Shared `LatencyIndicator` renders in the page header with `role="status"` (Biome a11y), value formatting (<1s → "N ms", else "X.Ys") and text-labeled states; NO_DATA renders "No data". The data-quality query joins `dataUpdatedAts`/`isLoading`/`isFetching`/`handleRefresh`; `computeOverallBanner` inputs untouched by design.
- **Pre-existing failure fixed in scope:** `MqttTelemetryIngestHandlerTest.handleMessageLogsReceivedForAcceptedMessage` failed on clean main (verified via `git stash -u` + isolated rerun at HEAD b116ad1) because its DEBUG-log assertion depended on the JVM's default logback level, which filtered debug events in this surefire environment. Since the file was already modified for tracker wiring, the test now pins `Level.DEBUG` in `attachAppender`/`detachAppender` (restoring inherit on detach), making it deterministic. Behavior of production code unchanged.
- Banner semantics, existing endpoint contracts, ingest validation/quarantine/backpressure behavior, and `HealthCard`'s read-only contract are all untouched; the panel and indicator are additive surfaces.

**Verification performed:**
- `mvn ... -Dtest="TelemetryDataQualityTrackerTest,TelemetryDataQualityServiceTest,TelemetryDataQualityControllerTest,MqttTelemetryIngestHandlerTest,TelemetryPersistenceServiceTest,IngestWorkerStatusServiceTest,TelemetryFreshnessServiceTest,TelemetryIngestQueueConfigTest"` — BUILD SUCCESS, 79 tests, 0 failures (all 8 touched classes, hermetic).
- `npm run test:unit` (syncro/apps/web) — 157 passed, 7 skipped (includes 11 new fetcher tests and 8 new page tests).
- `npm run build` (syncro/apps/web) — compiled successfully; `/dashboard/system-health` route built.
- `npx biome lint` on the 7 changed frontend files — clean (0 errors, 0 warnings; two nested-ternary warnings and two a11y aria-label errors found during dev were fixed).

**Residual risks / pre-existing failures (NOT caused by this story):**
- Full backend suite (`mvn test`) still carries the documented main-branch failures (cf. spec-6-5/6-6): `SparepartAlertCommandServiceTest.acknowledge_cancelsActiveNotificationJobs` (stale expectation), `WahaRateLimiterTest` strict-stubbing hygiene, and Testcontainers `influxdb:3-core` startup timeouts blocking `@SpringBootTest` classes in this environment. None are in files touched by 6-7.
- Latency measures publish → backend-visible (SM-008's platform portion); the browser poll interval (≤30s) is additional and intentionally not folded into the backend-owned metric (documented in Design Notes).
- Full-stack browser verification was not possible unattended; evidence is unit/build-level per the Verification section.

**Review verification (2026-08-22, after applying review patches):**
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="TelemetryDataQualityTrackerTest,TelemetryDataQualityServiceTest,TelemetryDataQualityControllerTest,MqttTelemetryIngestHandlerTest,TelemetryPersistenceServiceTest,IngestWorkerStatusServiceTest,TelemetryFreshnessServiceTest,TelemetryIngestQueueConfigTest,TelemetryPropertiesTest"` — Tests run: 87, Failures: 0, Errors: 0 (whole module compiles; +8 tests over the dev run: latency-window expiry, cross-minute concurrency hammer, 3 TelemetryPropertiesTest cases, service fixture fixes).
- `npm run test:unit` (syncro/apps/web) — 175 passed, 7 skipped (15 files; new `data-quality-panel.test.tsx` and `latency-indicator.test.tsx` included).
- `npm run build` (syncro/apps/web) — compiled successfully.
- `npx biome lint` on the 7 changed frontend files — clean (0 errors, 0 warnings).
- The dev-run verification numbers quoted above (79 backend / 157 frontend) predate the interrupted review patch application, which left the module non-compiling; they are superseded by this block.

### File List

- `syncro/apps/backend/src/main/java/com/syncro/config/TelemetryProperties.java` — modified (new validated `dataQuality.window` group)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryDataQualityTracker.java` — new
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryDataQualityState.java` — new
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/LatencyState.java` — new
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryDataQualityStatus.java` — new
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryDataQualityService.java` — new
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/api/TelemetryDataQualityController.java` — new
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java` — modified (tracker wiring)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` — modified (Clock + tracker + latency sampling)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryDataQualityTrackerTest.java` — new
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryDataQualityServiceTest.java` — new
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/api/TelemetryDataQualityControllerTest.java` — new
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestHandlerTest.java` — modified (tracker tests + deterministic DEBUG level)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestAtddScaffoldTest.java` — modified (constructor arg only)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceServiceTest.java` — modified (4 latency-sampling tests)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/IngestWorkerStatusServiceTest.java` — modified (properties constructor arg)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryFreshnessServiceTest.java` — modified (properties constructor arg)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/TelemetryIngestQueueConfigTest.java` — modified (properties constructor arg)
- `syncro/apps/web/src/features/system-health/types/index.ts` — modified (3 new types)
- `syncro/apps/web/src/features/system-health/hooks/use-data-quality.ts` — new
- `syncro/apps/web/src/features/system-health/hooks/use-data-quality.test.ts` — new
- `syncro/apps/web/src/components/syncro/data-quality-panel.tsx` — new
- `syncro/apps/web/src/components/syncro/latency-indicator.tsx` — new
- `syncro/apps/web/src/features/system-health/components/system-health-page.tsx` — modified (header indicator, Data Quality section, anchor, query wiring)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPayload.java` — modified during review (shared `REASON_OUT_OF_RANGE` constant aliased by the tracker; validation semantics byte-identical)
- `syncro/apps/backend/src/test/java/com/syncro/config/TelemetryPropertiesTest.java` — new during review (data-quality window validation boundaries)
- `syncro/apps/web/src/components/syncro/data-quality-panel.test.tsx` — new during review (window label contract, zero/error states, proto-key severity fallback)
- `syncro/apps/web/src/components/syncro/latency-indicator.test.tsx` — new during review (value formatting boundaries, label dedup, last-known-on-error)
- `syncro/apps/web/src/features/system-health/components/system-health-page.test.tsx` — modified (8 new tests + fixture/mock)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — modified (status transitions)
